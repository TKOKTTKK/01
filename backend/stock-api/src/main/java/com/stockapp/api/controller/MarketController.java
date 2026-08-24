package com.stockapp.api.controller;

import com.stockapp.common.result.Result;
import com.stockapp.common.vo.MarketIndexVO;
import com.stockapp.common.vo.StockVO;
import com.stockapp.service.HotStockService;
import com.stockapp.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;
    private final HotStockService hotStockService;

    /** 市场指数：上证 / 深证 / 创业板 */
    @GetMapping("/index")
    public Result<List<MarketIndexVO>> index() {
        return Result.success(marketService.getMarketIndex());
    }

    /** 热门股票 */
    @GetMapping("/hot")
    public Result<List<StockVO>> hot(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(hotStockService.hot(Math.min(Math.max(limit, 1), 20)));
    }
}
