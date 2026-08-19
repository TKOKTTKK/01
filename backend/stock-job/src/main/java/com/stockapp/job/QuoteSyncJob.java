package com.stockapp.job;

import com.stockapp.service.MarketService;
import com.stockapp.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 行情同步任务：定期刷新所有股票行情与市场指数到 Redis，
 * 保证首页 / 列表读取时大概率命中缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteSyncJob {

    private final StockService stockService;
    private final MarketService marketService;

    /** 每 8 秒刷新一次（行情缓存 TTL 为 10 秒） */
    @Scheduled(fixedDelay = 8_000, initialDelay = 5_000)
    public void syncQuotes() {
        try {
            stockService.listAll(); // 内部逐只获取行情并写入缓存
            marketService.getMarketIndex();
        } catch (Exception e) {
            log.error("行情同步失败: {}", e.getMessage());
        }
    }
}
