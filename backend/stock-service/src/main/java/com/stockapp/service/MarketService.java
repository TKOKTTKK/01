package com.stockapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stockapp.common.constant.RedisKeys;
import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.MarketIndexVO;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.service.market.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public IntradayVO getIntraday(String code) {
        return cache.getOrLoad(RedisKeys.intraday(code), RedisKeys.INTRADAY_TTL,
                new TypeReference<IntradayVO>() {},
                () -> provider.getIntraday(code));
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
