package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.util.IndicatorCalculator;
import com.stockapp.common.vo.IndicatorVO;
import com.stockapp.common.vo.KlineVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.StockKline;
import com.stockapp.dao.mapper.StockKlineMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** K 线与技术指标（数据来自数据库，指标统一由后端计算） */
@Service
@RequiredArgsConstructor
public class KlineService {

    private static final Set<String> PERIODS = Set.of("day", "week", "month");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 单次最多返回的 K 线数量，避免一次加载海量数据 */
    private static final int MAX_LIMIT = 500;

    private final StockKlineMapper klineMapper;
    private final StockService stockService;

    /** 按周期查询 K 线（升序），limit 默认 250，上限 500 */
    public List<KlineVO> getKline(String code, String period, Integer limit) {
        if (!PERIODS.contains(period)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                    "period 仅支持 day / week / month");
        }
        int size = limit == null ? 250 : Math.min(Math.max(limit, 1), MAX_LIMIT);
        Stock stock = stockService.getByCode(code);
        List<StockKline> rows = klineMapper.selectList(new LambdaQueryWrapper<StockKline>()
                .eq(StockKline::getStockId, stock.getId())
                .eq(StockKline::getPeriodType, period)
                .orderByDesc(StockKline::getTradeDate)
                .last("LIMIT " + size));
        return rows.stream()
                .sorted(Comparator.comparing(StockKline::getTradeDate))
                .map(k -> new KlineVO(k.getTradeDate().format(FMT),
                        k.getOpenPrice(), k.getHighPrice(), k.getLowPrice(),
                        k.getClosePrice(), k.getVolume(), k.getAmount()))
                .toList();
    }

    /** 技术指标：MA5/10/20/60、MACD、KDJ、RSI6/12/24，与 K 线一一对应 */
    public IndicatorVO getIndicators(String code, String period, Integer limit) {
        List<KlineVO> klines = getKline(code, period, limit);
        List<String> dates = klines.stream().map(KlineVO::getDate).toList();
        List<BigDecimal> closes = klines.stream().map(KlineVO::getClose).toList();
        List<BigDecimal> highs = klines.stream().map(KlineVO::getHigh).toList();
        List<BigDecimal> lows = klines.stream().map(KlineVO::getLow).toList();

        Map<String, List<BigDecimal>> ma = new LinkedHashMap<>();
        ma.put("ma5", IndicatorCalculator.ma(closes, 5));
        ma.put("ma10", IndicatorCalculator.ma(closes, 10));
        ma.put("ma20", IndicatorCalculator.ma(closes, 20));
        ma.put("ma60", IndicatorCalculator.ma(closes, 60));

        List<List<BigDecimal>> macdArr = IndicatorCalculator.macd(closes);
        Map<String, List<BigDecimal>> macd = new LinkedHashMap<>();
        macd.put("dif", macdArr.get(0));
        macd.put("dea", macdArr.get(1));
        macd.put("macd", macdArr.get(2));

        List<List<BigDecimal>> kdjArr = IndicatorCalculator.kdj(highs, lows, closes);
        Map<String, List<BigDecimal>> kdj = new LinkedHashMap<>();
        kdj.put("k", kdjArr.get(0));
        kdj.put("d", kdjArr.get(1));
        kdj.put("j", kdjArr.get(2));

        Map<String, List<BigDecimal>> rsi = new LinkedHashMap<>();
        rsi.put("rsi6", IndicatorCalculator.rsi(closes, 6));
        rsi.put("rsi12", IndicatorCalculator.rsi(closes, 12));
        rsi.put("rsi24", IndicatorCalculator.rsi(closes, 24));

        return IndicatorVO.builder()
                .dates(dates).ma(ma).macd(macd).kdj(kdj).rsi(rsi)
                .build();
    }
}
