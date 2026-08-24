package com.stockapp.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技术指标计算单元测试 */
class IndicatorCalculatorTest {

    private static List<BigDecimal> prices(double... vs) {
        return java.util.Arrays.stream(vs).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    void ma_shouldAverageLastNValues() {
        List<BigDecimal> ma = IndicatorCalculator.ma(prices(1, 2, 3, 4, 5), 5);
        assertNull(ma.get(3), "不足 5 个数据时应为 null");
        assertEquals(0, ma.get(4).compareTo(new BigDecimal("3")));

        List<BigDecimal> ma2 = IndicatorCalculator.ma(prices(10, 20, 30), 2);
        assertEquals(0, ma2.get(1).compareTo(new BigDecimal("15")));
        assertEquals(0, ma2.get(2).compareTo(new BigDecimal("25")));
    }

    @Test
    void macd_constantPrice_shouldBeZero() {
        List<BigDecimal> closes = IntStream.range(0, 60)
                .mapToObj(i -> BigDecimal.valueOf(100)).toList();
        List<List<BigDecimal>> macd = IndicatorCalculator.macd(closes);
        // 价格不变时 DIF/DEA/MACD 均为 0
        assertEquals(0, macd.get(0).get(59).compareTo(BigDecimal.ZERO));
        assertEquals(0, macd.get(1).get(59).compareTo(BigDecimal.ZERO));
        assertEquals(0, macd.get(2).get(59).compareTo(BigDecimal.ZERO));
    }

    @Test
    void macd_uptrend_difShouldBePositive() {
        List<BigDecimal> closes = IntStream.range(0, 60)
                .mapToObj(i -> BigDecimal.valueOf(100 + i)).toList();
        List<List<BigDecimal>> macd = IndicatorCalculator.macd(closes);
        assertTrue(macd.get(0).get(59).signum() > 0, "上涨趋势中 DIF 应为正");
    }

    @Test
    void kdj_shouldStayInReasonableRangeAndSatisfyJFormula() {
        List<BigDecimal> highs = prices(11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        List<BigDecimal> lows  = prices(9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
        List<BigDecimal> closes = prices(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        List<List<BigDecimal>> kdj = IndicatorCalculator.kdj(highs, lows, closes);
        for (int i = 0; i < closes.size(); i++) {
            BigDecimal k = kdj.get(0).get(i);
            BigDecimal d = kdj.get(1).get(i);
            BigDecimal j = kdj.get(2).get(i);
            // J = 3K - 2D
            BigDecimal expectJ = k.multiply(BigDecimal.valueOf(3))
                    .subtract(d.multiply(BigDecimal.valueOf(2)));
            assertTrue(j.subtract(expectJ).abs().doubleValue() < 0.01);
            assertTrue(k.doubleValue() >= 0 && k.doubleValue() <= 100);
        }
    }

    @Test
    void rsi_allUp_shouldBe100_allFlat_shouldBe50() {
        List<BigDecimal> up = IntStream.range(0, 20)
                .mapToObj(i -> BigDecimal.valueOf(100 + i)).toList();
        List<BigDecimal> rsi = IndicatorCalculator.rsi(up, 6);
        assertNull(rsi.get(5), "前 n 个位置应为 null");
        assertEquals(0, rsi.get(19).compareTo(new BigDecimal("100.000")), "全涨 RSI=100");

        List<BigDecimal> flat = IntStream.range(0, 20)
                .mapToObj(i -> BigDecimal.valueOf(100)).toList();
        assertEquals(0, IndicatorCalculator.rsi(flat, 6).get(19)
                .compareTo(new BigDecimal("50.000")), "横盘 RSI=50");
    }
}
