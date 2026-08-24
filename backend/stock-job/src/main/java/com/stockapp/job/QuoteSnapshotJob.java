package com.stockapp.job;

import com.stockapp.common.vo.QuoteVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.StockQuote;
import com.stockapp.dao.mapper.StockMapper;
import com.stockapp.dao.mapper.StockQuoteMapper;
import com.stockapp.service.MarketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 行情快照落库任务：每分钟保存一份行情快照（唯一约束防重） */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteSnapshotJob {

    private final StockMapper stockMapper;
    private final StockQuoteMapper quoteMapper;
    private final MarketService marketService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void snapshot() {
        List<Stock> stocks = stockMapper.selectList(null);
        LocalDateTime minute = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        for (Stock stock : stocks) {
            try {
                QuoteVO q = marketService.getQuote(stock.getCode(), stock.getName());
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
                row.setCreatedAt(LocalDateTime.now());
                quoteMapper.insert(row);
            } catch (DuplicateKeyException ignored) {
                // 同一分钟重复快照，直接忽略
            } catch (Exception e) {
                log.error("行情快照失败: code={}, err={}", stock.getCode(), e.getMessage());
            }
        }
    }
}
