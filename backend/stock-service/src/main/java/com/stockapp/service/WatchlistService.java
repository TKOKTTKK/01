package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.StockVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.UserWatchlist;
import com.stockapp.dao.mapper.UserWatchlistMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 自选股：仅操作当前登录用户自己的数据，数据库唯一约束防重 */
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final UserWatchlistMapper watchlistMapper;
    private final StockService stockService;

    public List<StockVO> list(Long userId) {
        List<UserWatchlist> rows = watchlistMapper.selectList(
                new LambdaQueryWrapper<UserWatchlist>()
                        .eq(UserWatchlist::getUserId, userId)
                        .orderByAsc(UserWatchlist::getSortOrder)
                        .orderByAsc(UserWatchlist::getId));
        return rows.stream()
                .map(w -> stockService.toVO(stockService.getById(w.getStockId())))
                .toList();
    }

    @Transactional
    public void add(Long userId, Long stockId) {
        Stock stock = stockService.getById(stockId); // 校验股票存在
        Long exists = watchlistMapper.selectCount(new LambdaQueryWrapper<UserWatchlist>()
                .eq(UserWatchlist::getUserId, userId)
                .eq(UserWatchlist::getStockId, stock.getId()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.WATCHLIST_DUPLICATE);
        }
        UserWatchlist w = new UserWatchlist();
        w.setUserId(userId);
        w.setStockId(stockId);
        w.setSortOrder(nextSortOrder(userId));
        w.setCreatedAt(LocalDateTime.now());
        try {
            watchlistMapper.insert(w);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.WATCHLIST_DUPLICATE);
        }
    }

    @Transactional
    public void remove(Long userId, Long stockId) {
        int deleted = watchlistMapper.delete(new LambdaQueryWrapper<UserWatchlist>()
                .eq(UserWatchlist::getUserId, userId)
                .eq(UserWatchlist::getStockId, stockId));
        if (deleted == 0) {
            throw new BizException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
    }

    public boolean contains(Long userId, Long stockId) {
        Long c = watchlistMapper.selectCount(new LambdaQueryWrapper<UserWatchlist>()
                .eq(UserWatchlist::getUserId, userId)
                .eq(UserWatchlist::getStockId, stockId));
        return c != null && c > 0;
    }

    private int nextSortOrder(Long userId) {
        UserWatchlist last = watchlistMapper.selectOne(new LambdaQueryWrapper<UserWatchlist>()
                .eq(UserWatchlist::getUserId, userId)
                .orderByDesc(UserWatchlist::getSortOrder)
                .last("LIMIT 1"));
        return last == null ? 1 : last.getSortOrder() + 1;
    }
}
