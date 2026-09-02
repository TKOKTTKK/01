package com.stockapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stockapp.common.constant.RedisKeys;
import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.MarketIndexVO;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.service.market.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 行情：实时报价 / 分时 / 指数（Redis 缓存 + 数据源抽象） */
@Service
@RequiredArgsConstructor
public class MarketService {

    private final MarketDataProvider provider;
    private final RedisCacheHelper cache;

    public QuoteVO getQuote(String code, String name) {
        return cache.getOrLoad(RedisKeys.quote(code), RedisKeys.QUOTE_TTL,
                new TypeReference<QuoteVO>() {},
                () -> provider.getQuote(code, name));
    }

    /**
     * 批量取行情：股票列表、行情快照定时任务等"要一次性拿一批股票的行情"
     * 场景专用，内部一次 MGET + 未命中并行回源，避免调用方逐只调 getQuote
     * 退化成串行 Redis 往返（股票池扩大后这是主要的响应耗时来源）。
     *
     * @param nameByCode code -> 股票名称，取自调用方已查出的 Stock 列表，
     *                   避免批量回源时再去查一次名称
     */
    public Map<String, QuoteVO> getQuotes(Map<String, String> nameByCode) {
        if (nameByCode.isEmpty()) {
            return Map.of();
        }
        List<String> codes = new ArrayList<>(nameByCode.keySet());
        return cache.getOrLoadBatch(codes, RedisKeys::quote, RedisKeys.QUOTE_TTL,
                new TypeReference<QuoteVO>() {},
                code -> provider.getQuote(code, nameByCode.get(code)));
    }

    public IntradayVO getIntraday(String code) {
        return cache.getOrLoad(RedisKeys.intraday(code), RedisKeys.INTRADAY_TTL,
                new TypeReference<IntradayVO>() {},
                () -> provider.getIntraday(code));
    }

    /**
     * 批量取分时：详情页批量首屏聚合（视口预取打包多只股票，见
     * StockService#detailBootstrapBatch）专用，内部一次 MGET + 未命中并行
     * 回源，跟 {@link #getQuotes} 是同一套批量 cache-aside，避免调用方
     * 循环调 getIntraday 退化成 N 次串行 Redis 往返。
     */
    public Map<String, IntradayVO> getIntradayBatch(List<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return cache.getOrLoadBatch(codes, RedisKeys::intraday, RedisKeys.INTRADAY_TTL,
                new TypeReference<IntradayVO>() {}, provider::getIntraday);
    }

    public List<MarketIndexVO> getMarketIndex() {
        return cache.getOrLoad(RedisKeys.MARKET_INDEX, RedisKeys.MARKET_INDEX_TTL,
                new TypeReference<List<MarketIndexVO>>() {},
                provider::getMarketIndex);
    }

    public boolean isMock() {
        return provider.isMock();
    }
}
