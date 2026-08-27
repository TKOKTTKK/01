package com.stockapp.job;

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
 * 【v2 批量化】原来是 for 循环逐只 getQuote + 逐条 insert，股票池小时无所谓，
 * 几千只股票每分钟一次就是几千次串行 Redis 调用 + 几千次串行 DB 往返。
 * 现在改成：一次批量取全部行情（MarketService#getQuotes，内部 1 次 MGET +
 * 未命中并行回源），再按 CHUNK_SIZE 分片批量插入（StockQuoteMapper#insertBatch，
 * 每片 1 条多值 INSERT + ON CONFLICT DO NOTHING 替代逐条 insert 捕获
 * DuplicateKeyException）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteSnapshotJob {

    /** 单条 INSERT 携带的行数上限：12 列 × 500 行 = 6000 个绑定参数，远低于
     *  PostgreSQL 单条 SQL 65535 个参数的上限，留足安全余量 */
    private static final int CHUNK_SIZE = 500;

    private final StockMapper stockMapper;
    private final StockQuoteMapper quoteMapper;
    private final MarketService marketService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
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
}
