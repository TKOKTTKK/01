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
import java.util.concurrent.ConcurrentHashMap;

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
    // 日 K：从固定锚点日期一路生成到昨天，再截取尾部 days 根。
    //
    // 【关键设计】随机游走必须从固定锚点开始走满全程，而不是从 days 天前开始走 days 步。
    // 否则同一个日历日的收盘价会随调用方传入的 days 不同而不同，导致
    // 「详情页昨收（内部取 2 天）」与「K线图最后一根（取 250 天）」对不上。
    // 现在 getKline 对同一 code + 同一日历日是纯函数，任意 days 都返回一致结果。
    // ---------------------------------------------------------------

    /** 随机游走锚点：所有历史序列的共同起点，改动此值会使全部历史数据变化 */
    private static final LocalDate ANCHOR = LocalDate.of(2024, 1, 2);

    @Override
    public List<KlineVO> getKline(String code, int days) {
        List<KlineVO> full = fullHistory(code);
        int from = Math.max(0, full.size() - Math.max(days, 1));
        return new ArrayList<>(full.subList(from, full.size()));
    }

    /** 进程内缓存：key = code@日期，同一天只生成一次全量历史 */
    private static final Map<String, List<KlineVO>> HISTORY_CACHE = new ConcurrentHashMap<>();

    /** 生成 ANCHOR ~ 昨天 的完整日 K 序列（同一 code 同一天结果恒定） */
    private static List<KlineVO> fullHistory(String code) {
        String key = code + "@" + LocalDate.now();
        List<KlineVO> cached = HISTORY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<KlineVO> generated = generateHistory(code);
        HISTORY_CACHE.keySet().removeIf(k -> !k.endsWith("@" + LocalDate.now())); // 跨天自动清理
        HISTORY_CACHE.put(key, generated);
        return generated;
    }

    /**
     * 均值回归强度。
     *
     * 【为什么必须有】游走现在从固定锚点走满全程，步数会随时间不断增长
     * （2026 年约 690 步，两年后约 1200 步）。纯随机游走的方差随步数线性增长，
     * 不做回归的话价格会越漂越远——实测茅台会从 1450 跌到 400 出头，明显失真。
     * 每步按 k 向基准价拉回一点，可让价格长期稳定在基准价上下 ±20% 区间内，
     * 同时保留短期趋势和波动的形态。
     */
    private static final double MEAN_REVERSION = 0.008;

    private static List<KlineVO> generateHistory(String code) {
        List<LocalDate> dates = tradingDaysSince(ANCHOR);
        double base = BASE_PRICE.getOrDefault(code, 20.0 + Math.abs(code.hashCode() % 80));
        Random seedRnd = new Random(code.hashCode() * 31L);

        List<KlineVO> list = new ArrayList<>(dates.size());
        double close = base * (0.75 + seedRnd.nextDouble() * 0.2); // 起点低于当前基准
        for (LocalDate d : dates) {
            Random r = rnd(code, d);
            double open = close * (1 + gauss(r) * 0.008);
            double c = open * (1 + gauss(r) * 0.018);
            c = c * (1 + MEAN_REVERSION * (base - c) / base);  // 向基准价缓慢回归
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

    /** 指定某个交易日的收盘价（供分时计算昨收，保证与 K 线完全一致） */
    public static BigDecimal closeOf(String code, LocalDate tradingDay) {
        String target = tradingDay.format(DATE_FMT);
        List<KlineVO> full = fullHistory(code);
        for (int i = full.size() - 1; i >= 0; i--) {
            if (full.get(i).getDate().equals(target)) {
                return full.get(i).getClose();
            }
        }
        return full.get(full.size() - 1).getClose();
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

        // 昨收 = quoteDay 前一交易日收盘。
        // 统一取自 fullHistory（与 K 线图、指标完全同源），
        // 不再按天数独立生成，杜绝「昨收与 K 线最后一根对不上」的问题。
        LocalDate preDay = previousTradingDay(quoteDay);
        double preClose = closeOf(code, preDay).doubleValue();

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
    /** 给定日期的前一个交易日（跳过周末） */
    private static LocalDate previousTradingDay(LocalDate day) {
        LocalDate d = day.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** 从 from 到昨天（含）的全部交易日，升序；序列随日期自然推进 */
    private static List<LocalDate> tradingDaysSince(LocalDate from) {
        List<LocalDate> out = new ArrayList<>(512);
        LocalDate end = LocalDate.now().minusDays(1);
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        if (out.isEmpty()) {
            out.add(end);
        }
        return out;
    }

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
