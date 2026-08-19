package com.stockapp.service.market;

import com.stockapp.common.vo.IntradayPointVO;
import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.KlineVO;
import com.stockapp.common.vo.MarketIndexVO;
import com.stockapp.common.vo.NewsVO;
import com.stockapp.common.vo.QuoteVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 模拟行情数据源（默认）。
 * 特点：
 * 1. 完全确定性：同一交易日生成的数据一致（随机种子 = code + 日期），刷新不跳变；
 * 2. 无需任何第三方 API Key，开箱即用；
 * 3. 数据形态贴近真实：随机游走 + 日内波动 + 成交量放缩。
 */
@Component
@ConditionalOnProperty(name = "market.data.provider", havingValue = "mock", matchIfMissing = true)
public class MockMarketDataProvider implements MarketDataProvider {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** 初始基准价（元） */
    private static final Map<String, Double> BASE_PRICE = Map.of(
            "600519", 1450.0, "000858", 128.0, "300750", 185.0, "002594", 265.0,
            "601318", 46.5, "600036", 34.2, "000001", 10.6, "601398", 5.8);

    @Override
    public String name() { return "mock"; }

    @Override
    public boolean isMock() { return true; }

    // ---------------------------------------------------------------
    // 日 K：以昨天为最后一根，向前生成 days 根（跳过周末）
    // ---------------------------------------------------------------
    @Override
    public List<KlineVO> getKline(String code, int days) {
        List<LocalDate> dates = lastTradingDays(days);
        double base = BASE_PRICE.getOrDefault(code, 20.0 + Math.abs(code.hashCode() % 80));
        Random seedRnd = new Random(code.hashCode() * 31L);
        double drift = (seedRnd.nextDouble() - 0.45) * 0.001; // 轻微趋势

        List<KlineVO> list = new ArrayList<>(dates.size());
        double close = base * (0.75 + seedRnd.nextDouble() * 0.2); // 起点低于当前基准
        for (LocalDate d : dates) {
            Random r = rnd(code, d);
            double open = close * (1 + gauss(r) * 0.008);
            double c = open * (1 + drift + gauss(r) * 0.018);
            c = clampChange(c, close);                    // 单日涨跌不超过 ±10%
            double high = Math.max(open, c) * (1 + r.nextDouble() * 0.012);
            double low = Math.min(open, c) * (1 - r.nextDouble() * 0.012);
            long volume = (long) (80_000 + r.nextDouble() * 400_000);   // 手
            double amount = volume * 100 * (high + low) / 2;
            list.add(new KlineVO(d.format(DATE_FMT),
                    dec(open), dec(high), dec(low), dec(c), volume, dec(amount)));
            close = c;
        }
        return list;
    }

    // ---------------------------------------------------------------
    // 实时行情：昨收 = 昨日日K收盘，日内价格随分钟推进
    // ---------------------------------------------------------------
    @Override
    public QuoteVO getQuote(String code, String stockName) {
        IntradayVO intraday = getIntraday(code);
        List<IntradayPointVO> pts = intraday.getPoints();
        IntradayPointVO last = pts.get(pts.size() - 1);
        BigDecimal preClose = intraday.getPreClose();
        BigDecimal price = last.getPrice();
        BigDecimal changeAmount = price.subtract(preClose);
        BigDecimal changePercent = preClose.signum() == 0 ? BigDecimal.ZERO
                : changeAmount.multiply(BigDecimal.valueOf(100))
                        .divide(preClose, 3, RoundingMode.HALF_UP);
        long volume = pts.stream().mapToLong(IntradayPointVO::getVolume).sum();
        BigDecimal amount = pts.stream().map(IntradayPointVO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal open = pts.get(0).getPrice();
        return QuoteVO.builder()
                .code(code).name(stockName)
                .price(price).openPrice(open)
                .highPrice(intraday.getHigh()).lowPrice(intraday.getLow())
                .preClose(preClose)
                .changeAmount(changeAmount.setScale(3, RoundingMode.HALF_UP))
                .changePercent(changePercent)
                .volume(volume).amount(amount.setScale(2, RoundingMode.HALF_UP))
                .tradeTime(LocalDateTime.now())
                .mock(true)
                .build();
    }

    // ---------------------------------------------------------------
    // 分时：09:30-11:30, 13:00-15:00，共 241 个点；
    // 交易时间内只生成截至当前分钟的点，收盘后/开盘前展示最近完整一天
    // ---------------------------------------------------------------
    @Override
    public IntradayVO getIntraday(String code) {
        LocalDate day = lastTradingDays(1).get(0); // 最近一个已收盘交易日
        LocalDate today = LocalDate.now();
        boolean weekday = today.getDayOfWeek() != DayOfWeek.SATURDAY
                && today.getDayOfWeek() != DayOfWeek.SUNDAY;
        LocalTime now = LocalTime.now();
        boolean inSession = weekday && !now.isBefore(LocalTime.of(9, 30))
                && now.isBefore(LocalTime.of(15, 0));
        LocalDate quoteDay = inSession || (weekday && now.isAfter(LocalTime.of(15, 0))) ? today : day;

        // 昨收 = quoteDay 前一交易日收盘（getKline 生成的序列以昨天为最后一根）
        List<KlineVO> hist = getKline(code, 2);
        double preClose = quoteDay.equals(today)
                ? hist.get(1).getClose().doubleValue()   // 今天的昨收 = 昨日收盘
                : hist.get(0).getClose().doubleValue();  // 历史日的昨收 = 前一日收盘

        List<LocalTime> allMinutes = tradingMinutes();
        int limit = allMinutes.size();
        if (inSession) {
            int count = 0;
            for (LocalTime t : allMinutes) {
                if (!t.isAfter(now)) count++;
            }
            limit = Math.max(count, 2);
        }

        Random r = rnd(code, quoteDay);
        double price = preClose * (1 + gauss(r) * 0.004);
        double high = price, low = price;
        double cumAmount = 0;
        long cumVolume = 0;
        List<IntradayPointVO> points = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            price = clampChange(price * (1 + gauss(r) * 0.0016), preClose);
            high = Math.max(high, price);
            low = Math.min(low, price);
            long vol = (long) (200 + r.nextDouble() * 3000);
            double amt = vol * 100 * price;
            cumVolume += vol;
            cumAmount += amt;
            double avg = cumAmount / (cumVolume * 100);
            points.add(new IntradayPointVO(allMinutes.get(i).format(TIME_FMT),
                    dec(price), dec(avg), vol, dec(amt)));
        }
        return IntradayVO.builder()
                .code(code).preClose(dec(preClose))
                .high(dec(high)).low(dec(low))
                .points(points).mock(true)
                .build();
    }

    // ---------------------------------------------------------------
    // 市场指数
    // ---------------------------------------------------------------
    @Override
    public List<MarketIndexVO> getMarketIndex() {
        record Idx(String code, String name, double base) {}
        List<Idx> defs = List.of(
                new Idx("000001.SH", "上证指数", 3350),
                new Idx("399001.SZ", "深证成指", 10850),
                new Idx("399006.SZ", "创业板指", 2230));
        LocalDate today = LocalDate.now();
        List<MarketIndexVO> out = new ArrayList<>(3);
        for (Idx idx : defs) {
            Random r = rnd(idx.code(), today);
            double pre = idx.base() * (0.97 + r.nextDouble() * 0.06);
            double cur = pre * (1 + gauss(r) * 0.008);
            double chg = cur - pre;
            out.add(new MarketIndexVO(idx.code(), idx.name(), dec2(cur),
                    dec2(chg), dec(chg / pre * 100)));
        }
        return out;
    }

    // ---------------------------------------------------------------
    // Mock 新闻
    // ---------------------------------------------------------------
    @Override
    public List<NewsVO> getNews(String code, String stockName, int limit) {
        String[] templates = {
                "%s发布最新经营数据，机构维持“增持”评级",
                "研报：%s核心业务稳健增长，长期价值凸显",
                "%s召开投资者交流会，回应市场关切",
                "行业观察：%s所处赛道景气度回升",
                "%s公告：拟加大研发投入，推进产品升级",
                "北向资金动向：%s获持续净买入",
                "%s三季报前瞻：营收有望保持双位数增长",
                "券商晨会聚焦%s：估值处于历史中位以下"
        };
        String[] sources = {"证券时报", "上海证券报", "财联社", "每日经济新闻", "第一财经"};
        Random r = new Random(code.hashCode() * 131L);
        List<NewsVO> list = new ArrayList<>(limit);
        LocalDateTime base = LocalDate.now().atTime(9, 0);
        for (int i = 0; i < limit; i++) {
            String title = templates[(r.nextInt(1000) + i) % templates.length].formatted(stockName);
            list.add(NewsVO.builder()
                    .stockCode(code)
                    .title(title)
                    .source(sources[(r.nextInt(1000) + i) % sources.length])
                    .url("https://example.com/news/" + code + "/" + i)
                    .content("【模拟新闻】" + title + "。本条内容由系统生成，仅用于演示，"
                            + "不构成任何投资建议。正式环境接入真实新闻源后本内容将被替换。")
                    .publishTime(base.minusHours(i * 5L).minusMinutes(r.nextInt(50)))
                    .build());
        }
        return list;
    }

    // ---------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------
    /** 最近 n 个交易日（不含今天，跳过周末），升序 */
    private static List<LocalDate> lastTradingDays(int n) {
        List<LocalDate> out = new ArrayList<>(n);
        LocalDate d = LocalDate.now().minusDays(1);
        while (out.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
            d = d.minusDays(1);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static List<LocalTime> tradingMinutes() {
        List<LocalTime> out = new ArrayList<>(241);
        LocalTime t = LocalTime.of(9, 30);
        while (!t.isAfter(LocalTime.of(11, 30))) { out.add(t); t = t.plusMinutes(1); }
        t = LocalTime.of(13, 1);
        while (!t.isAfter(LocalTime.of(15, 0))) { out.add(t); t = t.plusMinutes(1); }
        return out;
    }

    private static Random rnd(String code, LocalDate date) {
        return new Random(code.hashCode() * 1_000_003L + date.toEpochDay());
    }

    private static double gauss(Random r) {
        return Math.max(-3, Math.min(3, r.nextGaussian()));
    }

    /** 相对昨收限制 ±10% */
    private static double clampChange(double price, double preClose) {
        return Math.max(preClose * 0.9, Math.min(preClose * 1.1, price));
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal dec2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
