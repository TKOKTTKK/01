package com.stockapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stockapp.common.constant.RedisKeys;
import com.stockapp.common.vo.StockVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** 热门股票：按涨跌幅绝对值排序（Mock 场景下的合理近似），Redis 缓存 */
@Service
@RequiredArgsConstructor
public class HotStockService {

    private final StockService stockService;
    private final RedisCacheHelper cache;

    public List<StockVO> hot(int limit) {
        List<StockVO> all = cache.getOrLoad(RedisKeys.HOT, RedisKeys.HOT_TTL,
                new TypeReference<List<StockVO>>() {},
                () -> stockService.listAll().stream()
                        .sorted(Comparator.comparing(
                                (StockVO s) -> s.getChangePercent() == null
                                        ? BigDecimal.ZERO : s.getChangePercent().abs())
                                .reversed())
                        .toList());
        return all.size() > limit ? all.subList(0, limit) : all;
    }
}
