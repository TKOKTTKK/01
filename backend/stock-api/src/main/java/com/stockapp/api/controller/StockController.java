package com.stockapp.api.controller;

import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.Result;
import com.stockapp.common.vo.IndicatorVO;
import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.KlineVO;
import com.stockapp.common.vo.NewsVO;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.common.vo.StockVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.service.KlineService;
import com.stockapp.service.MarketService;
import com.stockapp.service.NewsService;
import com.stockapp.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final MarketService marketService;
    private final KlineService klineService;
    private final NewsService newsService;

    /** 股票搜索：GET /api/stocks/search?keyword=茅台 */
    @GetMapping("/search")
    public Result<List<StockVO>> search(@RequestParam String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "keyword 不能为空");
        }
        return Result.success(stockService.search(kw));
    }

    /** 股票列表 */
    @GetMapping
    public Result<List<StockVO>> list() {
        return Result.success(stockService.listAll());
    }

    /** 股票详情 */
    @GetMapping("/{code}")
    public Result<StockVO> detail(@PathVariable String code) {
        Stock stock = stockService.getByCode(code);
        return Result.success(stockService.toVO(stock));
    }

    /** 实时行情 */
    @GetMapping("/{code}/quote")
    public Result<QuoteVO> quote(@PathVariable String code) {
        Stock stock = stockService.getByCode(code);
        return Result.success(marketService.getQuote(stock.getCode(), stock.getName()));
    }

    /** 分时 */
    @GetMapping("/{code}/intraday")
    public Result<IntradayVO> intraday(@PathVariable String code) {
        stockService.getByCode(code); // 校验存在
        return Result.success(marketService.getIntraday(code));
    }

    /** K线：period = day | week | month */
    @GetMapping("/{code}/kline")
    public Result<List<KlineVO>> kline(@PathVariable String code,
                                       @RequestParam(defaultValue = "day") String period,
                                       @RequestParam(required = false) Integer limit) {
        return Result.success(klineService.getKline(code, period, limit));
    }

    /** 技术指标：MA / MACD / KDJ / RSI */
    @GetMapping("/{code}/indicators")
    public Result<IndicatorVO> indicators(@PathVariable String code,
                                          @RequestParam(defaultValue = "day") String period,
                                          @RequestParam(required = false) Integer limit) {
        return Result.success(klineService.getIndicators(code, period, limit));
    }

    /** 个股新闻 */
    @GetMapping("/{code}/news")
    public Result<List<NewsVO>> news(@PathVariable String code,
                                     @RequestParam(defaultValue = "20") int limit) {
        return Result.success(newsService.listByStock(code, limit));
    }
}
