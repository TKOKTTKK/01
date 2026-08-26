package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stockapp.common.constant.RedisKeys;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.common.vo.StockVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.mapper.StockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 股票基础信息：搜索 / 列表 / 详情 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockMapper stockMapper;
    private final MarketService marketService;
    private final RedisCacheHelper cache;

    /** 按代码或名称模糊搜索（code/name 均有索引） */
    public List<StockVO> search(String keyword) {
        LambdaQueryWrapper<Stock> qw = new LambdaQueryWrapper<Stock>()
                .eq(Stock::getStatus, 1)
                .and(w -> w.likeRight(Stock::getCode, keyword)
                        .or().like(Stock::getName, keyword))
                .last("LIMIT 20");
        return withQuote(stockMapper.selectList(qw));
    }

    public List<StockVO> listAll() {
        return withQuote(stockMapper.selectList(
                new LambdaQueryWrapper<Stock>().eq(Stock::getStatus, 1)
                        .orderByAsc(Stock::getId)));
    }

    /**
     * 按代码取股票基础信息。
     *
     * 详情页冷启动链路（bootstrap / kline / indicators / news）每个请求都要
     * 先过一次 getByCode 做存在性校验，等于每次页面打开要打 3-4 次同样的
     * 单行 SELECT。基础信息几乎不变，这里启用 RedisKeys.INFO（6 小时 TTL，
     * 该 key 此前已定义但一直未接线）做 cache-aside：
     * - 「不存在」不会被缓存：loader 抛 BizException 时直接向上传播，不写缓存；
     * - Redis 故障时 RedisCacheHelper 自动降级为直查 DB，行为与之前完全一致。
     */
    public Stock getByCode(String code) {
        return cache.getOrLoad(RedisKeys.info(code), RedisKeys.INFO_TTL,
                new TypeReference<Stock>() {},
                () -> loadByCode(code));
    }

    private Stock loadByCode(String code) {
        Stock stock = stockMapper.selectOne(
                new LambdaQueryWrapper<Stock>().eq(Stock::getCode, code));
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        return stock;
    }

    /** 批量按 id 取股票，返回 id -> Stock 映射（供持仓/成交列表避免 N+1 查询） */
    public java.util.Map<Long, Stock> mapByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Map.of();
        }
        return stockMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Stock::getId, x -> x));
    }

    public Stock getById(Long id) {
        Stock stock = stockMapper.selectById(id);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        return stock;
    }

    public StockVO toVO(Stock stock) {
        QuoteVO q = marketService.getQuote(stock.getCode(), stock.getName());
        return toVO(stock, q);
    }

    /** 已持有行情时复用，避免同一请求内重复取一次 quote（供 detail-bootstrap 使用） */
    public StockVO toVO(Stock stock, QuoteVO q) {
        return StockVO.builder()
                .id(stock.getId()).code(stock.getCode()).name(stock.getName())
                .market(stock.getMarket()).industry(stock.getIndustry())
                .price(q.getPrice())
                .changeAmount(q.getChangeAmount())
                .changePercent(q.getChangePercent())
                .build();
    }

    private List<StockVO> withQuote(List<Stock> stocks) {
        return stocks.stream().map(this::toVO).toList();
    }
}
