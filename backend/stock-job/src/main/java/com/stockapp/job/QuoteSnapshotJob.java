package com.stockapp.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.StockQuote;
import com.stockapp.dao.mapper.StockMapper;
import com.stockapp.dao.mapper.StockQuoteMapper;
import com.stockapp.service.MarketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 行情快照落库任务：每分钟保存一份行情快照。
 *
 * 【2026-09-01 停用】这张表只写不读的隐患（见下面 v2/保留策略的说明）
 * 变成了真实事故——线上 Postgres 磁盘被这张表写满，导致 stock-api
 * 启动失败（PSQLException: No space left on device）。30 天保留清理
 * 任务只能防止"未来"继续无限增长，挡不住事故当下已经堆起来的存量，
 * 而且应用启动不了时，清理任务自己也没法运行，等于失效。
 * 已经手动 TRUNCATE 清空过数据；这里把两个 @Scheduled 都注释掉，
 * 彻底停止再写入，不再依赖"定期清理来兜底"这种更脆弱的方式。
 * 代码保留（没有删除类/表/mapper）：如果以后真的有场景要用到分钟级
 * 历史快照（比如做回放/审计），把下面两个 @Scheduled 注解放开即可，
 * 不需要重新设计这部分逻辑。
 *
 * 【v2 批量化】原来是 for 循环逐只 getQuote + 逐条 insert，股票池小时无所谓，
 * 几千只股票每分钟一次就是几千次串行 Redis 调用 + 几千次串行 DB 往返。
 * 现在改成：一次批量取全部行情（MarketService#getQuotes，内部 1 次 MGET +
 * 未命中并行回源），再按 CHUNK_SIZE 分片批量插入（StockQuoteMapper#insertBatch，
 * 每片 1 条多值 INSERT + ON CONFLICT DO NOTHING 替代逐条 insert 捕获
 * DuplicateKeyException）。
 *
 * 【保留策略，已停用】这张表只写不读，如果永久保留，长期运行下去行数会
 * 无限增长（每分钟 × 股票数），拖慢备份/迁移，也是本次磁盘写满事故的
 * 直接原因。原本加了每日清理，只保留最近 {@link #RETENTION_DAYS} 天的
 * 快照——现在连写入都停了，这个清理任务也一并停用，没有新数据需要清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteSnapshotJob {

    /** 单条 INSERT 携带的行数上限：12 列 × 500 行 = 6000 个绑定参数，远低于
     *  PostgreSQL 单条 SQL 65535 个参数的上限，留足安全余量 */
    private static final int CHUNK_SIZE = 500;

    /** 快照保留天数，超过的行按每日清理任务删除（任务已停用，见类注释） */
    private static final int RETENTION_DAYS = 30;

    private final StockMapper stockMapper;
    private final StockQuoteMapper quoteMapper;
    private final MarketService marketService;

    // @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void snapshot() {
        List<Stock> stocks = stockMapper.selectList(null);
        if (stocks.isEmpty()) {
            return;
        }
        LocalDateTime minute = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime now = LocalDateTime.now();

        Map<String, String> nameByCode = stocks.stream()
                .collect(Collectors.toMap(Stock::getCode, Stock::getName, (a, b) -> a));
        Map<String, QuoteVO> quotes = marketService.getQuotes(nameByCode);

        List<StockQuote> rows = new ArrayList<>(stocks.size());
        for (Stock stock : stocks) {
            QuoteVO q = quotes.get(stock.getCode());
            if (q == null) {
                log.warn("行情快照跳过: code={} 未取到行情", stock.getCode());
                continue;
            }
            StockQuote row = new StockQuote();
            row.setStockId(stock.getId());
            row.setPrice(q.getPrice());
            row.setOpenPrice(q.getOpenPrice());
            row.setHighPrice(q.getHighPrice());
            row.setLowPrice(q.getLowPrice());
            row.setPreClose(q.getPreClose());
            row.setChangeAmount(q.getChangeAmount());
            row.setChangePercent(q.getChangePercent());
            row.setVolume(q.getVolume());
            row.setAmount(q.getAmount());
            row.setTradeTime(minute);
            row.setCreatedAt(now);
            rows.add(row);
        }

        int inserted = 0;
        for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
            List<StockQuote> chunk = rows.subList(from, Math.min(from + CHUNK_SIZE, rows.size()));
            try {
                inserted += quoteMapper.insertBatch(chunk);
            } catch (Exception e) {
                log.error("行情快照批量写入失败: chunkSize={}, err={}", chunk.size(), e.getMessage());
            }
        }
        log.info("行情快照完成: {}/{} 只股票写入（重复的一分钟内快照会被 ON CONFLICT 跳过）",
                inserted, stocks.size());
    }

    /**
     * 每日清理超过保留期的快照（任务已停用，见类注释——snapshot() 不再
     * 写入，没有新数据需要清理，保留方法体以便将来重新启用整套逻辑）。
     * 用主键批量删除而不是一次性 DELETE 大范围行，避免长事务长时间锁表——
     * 落库这张表虽然只写不读，但删除时仍会跟当分钟的 INSERT 竞争锁，
     * 分批删除让每一批的锁持有时间都很短。
     */
    // @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void cleanupOldSnapshots() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long total = 0;
        while (true) {
            List<StockQuote> batch = quoteMapper.selectList(new LambdaQueryWrapper<StockQuote>()
                    .lt(StockQuote::getTradeTime, cutoff)
                    .select(StockQuote::getId)
                    .last("LIMIT " + CHUNK_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            List<Long> ids = batch.stream().map(StockQuote::getId).toList();
            quoteMapper.deleteBatchIds(ids);
            total += ids.size();
            if (ids.size() < CHUNK_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("行情快照清理完成: 删除 {} 天前的快照共 {} 条", RETENTION_DAYS, total);
        }
    }
}
