package com.stockapp.service;

import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.UserWatchlist;
import com.stockapp.dao.mapper.UserWatchlistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 自选股测试：防重复 / 删除不存在 */
@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    @Mock
    private UserWatchlistMapper watchlistMapper;
    @Mock
    private StockService stockService;

    private WatchlistService watchlistService;

    @BeforeEach
    void setUp() {
        watchlistService = new WatchlistService(watchlistMapper, stockService);
    }

    @Test
    void add_duplicate_shouldThrow() {
        Stock s = new Stock();
        s.setId(1L);
        when(stockService.getById(anyLong())).thenReturn(s);
        when(watchlistMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> watchlistService.add(100L, 1L));
        assertEquals(ErrorCode.WATCHLIST_DUPLICATE.getCode(), e.getCode());
        verify(watchlistMapper, never()).insert(any(UserWatchlist.class));
    }

    @Test
    void add_new_shouldInsert() {
        Stock s = new Stock();
        s.setId(2L);
        when(stockService.getById(anyLong())).thenReturn(s);
        when(watchlistMapper.selectCount(any())).thenReturn(0L);
        when(watchlistMapper.selectOne(any())).thenReturn(null);
        when(watchlistMapper.insert(any(UserWatchlist.class))).thenReturn(1);

        watchlistService.add(100L, 2L);
        verify(watchlistMapper).insert(any(UserWatchlist.class));
    }

    @Test
    void remove_notExists_shouldThrow() {
        when(watchlistMapper.delete(any())).thenReturn(0);
        BizException e = assertThrows(BizException.class,
                () -> watchlistService.remove(100L, 5L));
        assertEquals(ErrorCode.WATCHLIST_NOT_FOUND.getCode(), e.getCode());
    }
}
