package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.NewsVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.StockNews;
import com.stockapp.dao.mapper.StockNewsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 股票新闻 */
@Service
@RequiredArgsConstructor
public class NewsService {

    private final StockNewsMapper newsMapper;
    private final StockService stockService;

    public List<NewsVO> listByStock(String code, int limit) {
        Stock stock = stockService.getByCode(code);
        List<StockNews> rows = newsMapper.selectList(new LambdaQueryWrapper<StockNews>()
                .eq(StockNews::getStockId, stock.getId())
                .orderByDesc(StockNews::getPublishTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
        return rows.stream().map(n -> toVO(n, stock.getCode())).toList();
    }

    public NewsVO getById(Long id) {
        StockNews news = newsMapper.selectById(id);
        if (news == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "新闻不存在");
        }
        String code = news.getStockId() == null ? null
                : stockService.getById(news.getStockId()).getCode();
        return toVO(news, code);
    }

    private NewsVO toVO(StockNews n, String code) {
        return NewsVO.builder()
                .id(n.getId()).stockCode(code)
                .title(n.getTitle()).source(n.getSource()).url(n.getUrl())
                .content(n.getContent()).publishTime(n.getPublishTime())
                .build();
    }
}
