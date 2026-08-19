package com.stockapp.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标统一计算逻辑（MA / MACD / KDJ / RSI）。
 * 前端不做指标计算，全部以后端结果为准，保证一致性。
 * 输入均为按时间升序排列的价格序列。
 * 序列前部无法计算的点返回 null。
 */
public final class IndicatorCalculator {

    private static final int SCALE = 3;

    private IndicatorCalculator() {}

    /** 简单移动平均 MA(n)，不足 n 个数据的位置为 null */
    public static List<BigDecimal> ma(List<BigDecimal> closes, int n) {
        List<BigDecimal> out = new ArrayList<>(closes.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < closes.size(); i++) {
            sum = sum.add(closes.get(i));
            if (i >= n) {
                sum = sum.subtract(closes.get(i - n));
            }
            if (i >= n - 1) {
                out.add(sum.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.HALF_UP));
            } else {
                out.add(null);
            }
        }
        return out;
    }

    /** 指数移动平均 EMA(n)，首值取第一个收盘价 */
    static List<BigDecimal> ema(List<BigDecimal> closes, int n) {
        List<BigDecimal> out = new ArrayList<>(closes.size());
        if (closes.isEmpty()) return out;
        BigDecimal k = BigDecimal.valueOf(2.0 / (n + 1));
        BigDecimal one = BigDecimal.ONE;
        BigDecimal prev = closes.get(0);
        out.add(prev.setScale(SCALE, RoundingMode.HALF_UP));
        for (int i = 1; i < closes.size(); i++) {
            // EMA = close*k + prevEMA*(1-k)
            prev = closes.get(i).multiply(k)
                    .add(prev.multiply(one.subtract(k)))
                    .setScale(6, RoundingMode.HALF_UP);
            out.add(prev.setScale(SCALE, RoundingMode.HALF_UP));
        }
        return out;
    }

    /** MACD(12,26,9)：返回 [DIF, DEA, MACD]，MACD = (DIF - DEA) * 2 */
    public static List<List<BigDecimal>> macd(List<BigDecimal> closes) {
        List<BigDecimal> ema12 = ema(closes, 12);
        List<BigDecimal> ema26 = ema(closes, 26);
        List<BigDecimal> dif = new ArrayList<>(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            dif.add(ema12.get(i).subtract(ema26.get(i)).setScale(SCALE, RoundingMode.HALF_UP));
        }
        List<BigDecimal> dea = ema(dif, 9);
        List<BigDecimal> macd = new ArrayList<>(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            macd.add(dif.get(i).subtract(dea.get(i))
                    .multiply(BigDecimal.valueOf(2))
                    .setScale(SCALE, RoundingMode.HALF_UP));
        }
        return List.of(dif, dea, macd);
    }

    /** KDJ(9,3,3)：返回 [K, D, J]，K/D 初始值 50 */
    public static List<List<BigDecimal>> kdj(List<BigDecimal> highs, List<BigDecimal> lows,
                                             List<BigDecimal> closes) {
        int size = closes.size();
        int n = 9;
        List<BigDecimal> ks = new ArrayList<>(size);
        List<BigDecimal> ds = new ArrayList<>(size);
        List<BigDecimal> js = new ArrayList<>(size);
        double k = 50, d = 50;
        for (int i = 0; i < size; i++) {
            int from = Math.max(0, i - n + 1);
            double high = Double.MIN_VALUE, low = Double.MAX_VALUE;
            for (int j = from; j <= i; j++) {
                high = Math.max(high, highs.get(j).doubleValue());
                low = Math.min(low, lows.get(j).doubleValue());
            }
            double rsv = high == low ? 50
                    : (closes.get(i).doubleValue() - low) / (high - low) * 100;
            k = k * 2 / 3 + rsv / 3;
            d = d * 2 / 3 + k / 3;
            double j = 3 * k - 2 * d;
            ks.add(BigDecimal.valueOf(k).setScale(SCALE, RoundingMode.HALF_UP));
            ds.add(BigDecimal.valueOf(d).setScale(SCALE, RoundingMode.HALF_UP));
            js.add(BigDecimal.valueOf(j).setScale(SCALE, RoundingMode.HALF_UP));
        }
        return List.of(ks, ds, js);
    }

    /** RSI(n)，Wilder 平滑；前 n 个位置为 null */
    public static List<BigDecimal> rsi(List<BigDecimal> closes, int n) {
        int size = closes.size();
        List<BigDecimal> out = new ArrayList<>(size);
        if (size == 0) return out;
        out.add(null);
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i < size; i++) {
            double diff = closes.get(i).doubleValue() - closes.get(i - 1).doubleValue();
            double gain = Math.max(diff, 0);
            double loss = Math.max(-diff, 0);
            if (i <= n) {
                avgGain += gain;
                avgLoss += loss;
                if (i == n) {
                    avgGain /= n;
                    avgLoss /= n;
                    out.add(rsiValue(avgGain, avgLoss));
                } else {
                    out.add(null);
                }
            } else {
                avgGain = (avgGain * (n - 1) + gain) / n;
                avgLoss = (avgLoss * (n - 1) + loss) / n;
                out.add(rsiValue(avgGain, avgLoss));
            }
        }
        return out;
    }

    private static BigDecimal rsiValue(double avgGain, double avgLoss) {
        double sum = avgGain + avgLoss;
        double rsi = sum == 0 ? 50 : avgGain / sum * 100;
        return BigDecimal.valueOf(rsi).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
