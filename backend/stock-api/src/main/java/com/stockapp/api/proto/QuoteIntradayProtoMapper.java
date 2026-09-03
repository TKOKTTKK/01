package com.stockapp.api.proto;

import com.stockapp.common.proto.IntradayPointDelta;
import com.stockapp.common.proto.QuoteIntradayVO;
import com.stockapp.common.util.TradingMinuteOffset;
import com.stockapp.common.vo.IntradayPointVO;
import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.QuoteVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 把现有的 {@link QuoteVO} + {@link IntradayVO}（服务层照旧产出的 POJO，
 * 一个字节都不用改）转换成 quote_intraday.proto 定义的二进制消息。
 *
 * 所有价格/金额类字段乘以 {@link #SCALE} 转成定点整数；分时点里的
 * 价格/均价/成交额只存相对上一个点的差值，具体原因和换算规则见
 * .proto 文件顶部注释——这里只放实现，设计取舍不重复写第二遍。
 */
public final class QuoteIntradayProtoMapper {

    /** 价格/金额定点换算精度：真实值 * 100（对应两位小数） */
    private static final long SCALE = 100L;
    private static final ZoneId ZONE_SH = ZoneId.of("Asia/Shanghai");

    private QuoteIntradayProtoMapper() {
    }

    public static QuoteIntradayVO toProto(QuoteVO quote, IntradayVO intraday) {
        QuoteIntradayVO.Builder builder = QuoteIntradayVO.newBuilder()
                .setCode(quote.getCode())
                .setName(quote.getName())
                .setPrice(fixed(quote.getPrice()))
                .setPreClose(fixed(quote.getPreClose()))
                .setChangeAmount(fixed(quote.getChangeAmount()))
                .setChangePercent(fixedPercent(quote.getChangePercent()))
                .setVolume(quote.getVolume() == null ? 0L : quote.getVolume())
                .setAmount(fixed(quote.getAmount()))
                .setTradeTime(epochMillis(quote.getTradeTime()))
                .setMock(Boolean.TRUE.equals(quote.getMock()))
                .setOpenPrice(fixed(quote.getOpenPrice()))
                .setHighPrice(fixed(quote.getHighPrice()))
                .setLowPrice(fixed(quote.getLowPrice()))
                .setIntradayPreClose(fixed(intraday.getPreClose()))
                .setIntradayHigh(fixed(intraday.getHigh()))
                .setIntradayLow(fixed(intraday.getLow()));

        List<IntradayPointVO> points = intraday.getPoints();
        if (points == null || points.isEmpty()) {
            return builder.build();
        }

        // 差值链的起点：第一个点存绝对值
        IntradayPointVO first = points.get(0);
        long prevPrice = fixed(first.getPrice());
        long prevAvgPrice = fixed(first.getAvgPrice());
        long prevAmount = fixed(first.getAmount());
        builder.setFirstPrice(prevPrice)
               .setFirstAvgPrice(prevAvgPrice)
               .setFirstAmount(prevAmount);
        builder.addPoints(IntradayPointDelta.newBuilder()
                .setMinuteOffset(TradingMinuteOffset.toOffset(first.getTime()))
                .setPriceDelta(0)
                .setAvgPriceDelta(0)
                .setVolume(first.getVolume() == null ? 0L : first.getVolume())
                .setAmountDelta(0)
                .build());

        // 从第二个点开始，每个点相对上一个点做差值
        for (int i = 1; i < points.size(); i++) {
            IntradayPointVO p = points.get(i);
            long price = fixed(p.getPrice());
            long avgPrice = fixed(p.getAvgPrice());
            long amount = fixed(p.getAmount());

            builder.addPoints(IntradayPointDelta.newBuilder()
                    .setMinuteOffset(TradingMinuteOffset.toOffset(p.getTime()))
                    .setPriceDelta(price - prevPrice)
                    .setAvgPriceDelta(avgPrice - prevAvgPrice)
                    .setVolume(p.getVolume() == null ? 0L : p.getVolume())
                    .setAmountDelta(amount - prevAmount)
                    .build());

            prevPrice = price;
            prevAvgPrice = avgPrice;
            prevAmount = amount;
        }
        return builder.build();
    }

    /** BigDecimal -> 定点 long（* 100，四舍五入），null 视为 0 */
    private static long fixed(BigDecimal v) {
        if (v == null) return 0L;
        return v.multiply(BigDecimal.valueOf(SCALE)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 涨跌幅（真实百分比，如 1.23 表示 1.23%）-> int32 定点 * 100 */
    private static int fixedPercent(BigDecimal v) {
        if (v == null) return 0;
        return v.multiply(BigDecimal.valueOf(SCALE)).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static long epochMillis(java.time.LocalDateTime t) {
        if (t == null) return 0L;
        return ZonedDateTime.of(t, ZONE_SH).toInstant().toEpochMilli();
    }
}
