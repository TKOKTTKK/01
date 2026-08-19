package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public Stock getByCode(String code) {
        Stock stock = stockMapper.selectOne(
                new LambdaQueryWrapper<Stock>().eq(Stock::getCode, code));
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        return stock;
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
