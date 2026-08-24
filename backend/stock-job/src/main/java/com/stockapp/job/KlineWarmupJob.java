package com.stockapp.job;

import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.mapper.StockMapper;
import com.stockapp.service.KlineService;
import com.stockapp.service.init.MockDataInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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
 * 当前全量只有 8 只股票，直接全部预热（8 股 × 3 周期，指标固定预热前端
 * 唯一使用的 limit=250）；将来股票池扩大再收敛到热门股 + 自选股集合。
 * Redis 不可用时 RedisCacheHelper 自动降级，本任务只会多打几条 warn 日志，
 * 不影响任何在线请求。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class KlineWarmupJob implements ApplicationRunner {

    private static final List<String> PERIODS = List.of("day", "week", "month");
    /** 前端 api/index.ts 固定传 limit=250，也是接口的默认值 */
    private static final int WARM_LIMIT = 250;

    private final StockMapper stockMapper;
    private final KlineService klineService;
    private final MockDataInitializer mockDataInitializer;

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
        int ok = 0;
        for (Stock stock : stocks) {
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
        log.info("{}完成: {}/{} 只股票, 耗时 {}ms",
                reason, ok, stocks.size(), System.currentTimeMillis() - start);
    }
}
