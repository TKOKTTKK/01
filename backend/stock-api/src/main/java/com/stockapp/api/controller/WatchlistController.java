package com.stockapp.api.controller;

import com.stockapp.api.security.CurrentUser;
import com.stockapp.common.result.Result;
import com.stockapp.common.vo.StockVO;
import com.stockapp.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 自选股：仅操作当前登录用户自己的数据 */
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @GetMapping
    public Result<List<StockVO>> list() {
        return Result.success(watchlistService.list(CurrentUser.id()));
    }

    @PostMapping("/{stockId}")
    public Result<Void> add(@PathVariable Long stockId) {
        watchlistService.add(CurrentUser.id(), stockId);
        return Result.success();
    }

    @DeleteMapping("/{stockId}")
    public Result<Void> remove(@PathVariable Long stockId) {
        watchlistService.remove(CurrentUser.id(), stockId);
        return Result.success();
    }

    /** 是否已在自选中（详情页按钮状态） */
    @GetMapping("/contains/{stockId}")
    public Result<Boolean> contains(@PathVariable Long stockId) {
        return Result.success(watchlistService.contains(CurrentUser.id(), stockId));
    }
}
