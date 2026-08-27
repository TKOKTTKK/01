package com.stockapp.job;

import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.mapper.StockMapper;
import com.stockapp.service.HotStockService;
import com.stockapp.service.KlineService;
import com.stockapp.service.StockService;
import com.stockapp.service.WatchlistService;
import com.stockapp.service.init.MockDataInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * K 线 / 指标缓存预热任务。
 *
 * K 线数据一天只变一次（跨天追加一根），所以预热没有放进 8 秒一轮的
 * QuoteSyncJob「顺带做」——那是行情的节奏，不是 K 线的节奏。单独用
 * 三个触发点，全部幂等：
 *
 * 1. 启动后立即预热（ApplicationRunner，@Order(2) 在 MockDataInitializer
 *    之后）：Render 实例被唤醒 / 重新部署后，第一位用户到来前缓存已就位，
 *    冷启动不再叠加「首次 K 线计算」的延迟；
 * 2. 每日 00:05（Asia/Shanghai）：先调 catchUpAll() 把新一根日 K 补进 DB，
 *    再预热 —— 按日期分片的缓存 key 在零点已切换到新值，此时写入的就是
 *    补齐后的新序列，用户白天访问全程命中；
 * 3. 每 6 小时续热一次：缓存命中不会重置 TTL（刻意如此，否则 quote 等
 *    短 TTL 缓存在持续访问下会永不过期），所以 26h TTL 到期后缓存会掉；
 *    本触发点保证过期后最多 6 小时内重新装填，覆盖周末 key 三天不变
 *    （周六/周日/周一的「应有最新交易日」都是上周五）的场景。
 *    过期到重装填的空窗内用户请求只是直查一次 DB（毫秒级），无感。
 *
 * 【v2 收敛策略】全量预热是 O(股票数 × 周期数)，股票池小的时候（早期/测试
 * 环境）直接全量最简单可靠；股票池一旦扩大到几千只，3 个周期 × 2 个接口
 * 全量跑一遍会有数万次缓存计算，拖慢启动/唤醒时间。超过
 * {@link #FULL_WARM_THRESHOLD} 后收敛到"大概率会被访问"的子集：
 * 热门股（涨跌幅靠前，见 HotStockService）+ 全站用户自选股的并集。
 * 不在这个子集里的股票不是不能访问，只是没有预热——用户点进详情页时
 * 现算一次（毫秒级）写入缓存，之后访问一样是零等待，只是「谁先访问谁
 * 触发那一次」，不影响正确性，只是没有"抢跑"。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class KlineWarmupJob implements ApplicationRunner {

    private static final List<String> PERIODS = List.of("day", "week", "month");
    /** 前端 api/index.ts 固定传 limit=250，也是接口的默认值 */
    private static final int WARM_LIMIT = 250;
    /** 股票总数不超过这个阈值时，收敛没有意义，直接全量预热 */
    private static final int FULL_WARM_THRESHOLD = 200;
    /** 热门股预热范围：比首页展示的 6 只更宽，覆盖用户点进"热门"里随便点开的情况 */
    private static final int HOT_WARM_LIMIT = 50;

    private final StockMapper stockMapper;
    private final KlineService klineService;
    private final MockDataInitializer mockDataInitializer;
    private final HotStockService hotStockService;
    private final WatchlistService watchlistService;
    private final StockService stockService;

    /** 启动预热：MockDataInitializer(@Order(1)) 已先完成补齐，这里直接热缓存 */
    @Override
    public void run(ApplicationArguments args) {
        warmAll("启动预热");
    }

    /** 每日跨天：先补当日新 K 线，再预热新日期分片的缓存 */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    public void dailyRollover() {
        try {
            mockDataInitializer.catchUpAll();
        } catch (Exception e) {
            log.error("跨天补齐 K 线失败: {}", e.getMessage());
        }
        warmAll("每日跨天预热");
    }

    /** 周期续热：TTL 到期掉缓存后最多 6h 内重新装填（跨周末 key 不变时兜底） */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 6 * 60 * 60 * 1000L)
    public void periodicRefresh() {
        warmAll("周期续热");
    }

    private void warmAll(String reason) {
        long start = System.currentTimeMillis();
        List<Stock> stocks = stockMapper.selectList(null);
        List<Stock> targets = stocks.size() <= FULL_WARM_THRESHOLD ? stocks : narrowToPriority(stocks);

        int ok = 0;
        for (Stock stock : targets) {
            try {
                for (String period : PERIODS) {
                    // getKline 写入全量序列缓存；getIndicators 复用它并写入指标缓存
                    klineService.getKline(stock.getCode(), period, WARM_LIMIT);
                    klineService.getIndicators(stock.getCode(), period, WARM_LIMIT);
                }
                ok++;
            } catch (Exception e) {
                log.error("K线预热失败: code={}, err={}", stock.getCode(), e.getMessage());
            }
        }
        log.info("{}完成: {}/{} 只股票预热（股票池共 {} 只）, 耗时 {}ms",
                reason, ok, targets.size(), stocks.size(), System.currentTimeMillis() - start);
    }

    /** 收敛到热门股 + 全站自选股的并集；任一来源取失败都不影响另一来源，也不影响主流程 */
    private List<Stock> narrowToPriority(List<Stock> stocks) {
        Set<String> priorityCodes = new LinkedHashSet<>();
        try {
            hotStockService.hot(HOT_WARM_LIMIT).forEach(s -> priorityCodes.add(s.getCode()));
        } catch (Exception e) {
            log.warn("预热收敛：获取热门股失败，跳过热门股部分: {}", e.getMessage());
        }
        try {
            List<Long> watchedIds = watchlistService.distinctStockIds();
            stockService.mapByIds(watchedIds).values().forEach(s -> priorityCodes.add(s.getCode()));
        } catch (Exception e) {
            log.warn("预热收敛：获取自选股集合失败，跳过自选股部分: {}", e.getMessage());
        }
        return stocks.stream().filter(s -> priorityCodes.contains(s.getCode())).toList();
    }
}
