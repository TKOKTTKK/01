package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stockapp.common.constant.RedisKeys;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K 线与技术指标（数据来自数据库，指标统一由后端计算）。
 *
 * 【缓存设计（v3.1 首屏优化）】
 * 1. 以 (code, period, day) 为 key 缓存「全量序列」（最多 MAX_LIMIT 根），
 *    任意 limit 都从同一份缓存序列截尾返回 —— 同一天不同 limit 的结果
 *    出自同一快照，V3 修复的「任意 limit 同日 OHLC 必须一致」不变量
 *    在缓存层面按构造成立，不可能被打破。
 * 2. key 中的 day = 该序列应有的最新交易日（昨天往前找到的第一个工作日，
 *    与 MockMarketDataProvider 的交易日规则一致）。日期滚动 → key 变化 →
 *    自动失效，不靠 TTL 过期，缓存不会滞后于「今日新 K 线」。
 * 3. 若 DB 尚未补上新一根（跨天后补齐任务还没跑），序列最后一根 < day，
 *    此时只回源不写缓存（见 RedisCacheHelper 的 cacheable 判定），
 *    避免把旧序列钉死在新 key 上；补齐完成后首次访问自然转为可缓存。
 * 4. 指标改为按 (code, period, MAX_LIMIT, day) 缓存一份"全量"结果
 *    （不再按前端传入的 limit 分别缓存），getIndicators 的 tail 视图和
 *    getIndicatorsSince 的增量视图都从这一份切片派生，不重复计算。
 * 5. Redis 故障时 RedisCacheHelper 自动降级为直查 DB，行情功能不受影响。
 *
 * 【增量拉取】getKlineSince / getIndicatorsSince：客户端本地已经缓存了
 * 历史序列时，只需要带着本地最新一根的日期来问"这之后还有什么新的"，
 * 不用每次都把全部历史重新传一遍。两者都复用同一份全量缓存序列做过滤/
 * 切片，因此增量视图与全量视图必然是同一份数据的子集，不会出现两个
 * 接口"看到的不是同一份快照"的不一致。
 */
@Service
@RequiredArgsConstructor
public class KlineService {

    private static final Set<String> PERIODS = Set.of("day", "week", "month");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_KEY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 单次最多返回的 K 线数量，避免一次加载海量数据 */
    private static final int MAX_LIMIT = 500;

    private final StockKlineMapper klineMapper;
    private final StockService stockService;
    private final RedisCacheHelper cache;

    /** 按周期查询 K 线（升序），limit 默认 250，上限 500 */
    public List<KlineVO> getKline(String code, String period, Integer limit) {
        requireValidPeriod(period);
        int size = normalizeLimit(limit);
        List<KlineVO> full = fullSeriesCached(code, period);
        return tail(full, size);
    }

    /**
     * 增量 K 线：只返回严格晚于 sinceDate（yyyy-MM-dd）的新记录，升序。
     * sinceDate 为空则等价于 getKline 的全量/tail 语义。
     *
     * 复用 fullSeriesCached 同一份缓存序列，跟全量接口构造上保证数据一致，
     * 不会出现"全量和增量看到的是两份不同快照"的问题。
     */
    public List<KlineVO> getKlineSince(String code, String period, String sinceDate) {
        requireValidPeriod(period);
        if (sinceDate == null || sinceDate.isBlank()) {
            return getKline(code, period, null);
        }
        List<KlineVO> full = fullSeriesCached(code, period);
        return full.stream().filter(k -> k.getDate().compareTo(sinceDate) > 0).toList();
    }

    /** 技术指标：MA5/10/20/60、MACD、KDJ、RSI6/12/24，与 K 线一一对应 */
    public IndicatorVO getIndicators(String code, String period, Integer limit) {
        requireValidPeriod(period);
        int size = normalizeLimit(limit);
        IndicatorVO full = fullIndicatorsCached(code, period);
        int total = full.getDates().size();
        return sliceIndicators(full, Math.max(0, total - size), total);
    }

    /**
     * 增量指标：只返回严格晚于 sinceDate 的新记录，升序。
     * sinceDate 为空则等价于 getIndicators 的全量/tail 语义。
     */
    public IndicatorVO getIndicatorsSince(String code, String period, String sinceDate) {
        requireValidPeriod(period);
        if (sinceDate == null || sinceDate.isBlank()) {
            return getIndicators(code, period, null);
        }
        IndicatorVO full = fullIndicatorsCached(code, period);
        List<String> dates = full.getDates();
        int from = dates.size();
        for (int i = 0; i < dates.size(); i++) {
            if (dates.get(i).compareTo(sinceDate) > 0) { from = i; break; }
        }
        return sliceIndicators(full, from, dates.size());
    }

    /**
     * 全量指标缓存：不依赖 limit（固定用 MAX_LIMIT 落缓存 key），
     * getIndicators 的 tail 视图和 getIndicatorsSince 的增量视图都从这一份
     * 派生，只切片不重复计算。相比 v3.1 的按 limit 分别缓存，副作用是
     * 计算窗口从"只看 limit 根"变成"看 MAX_LIMIT 根"，指标在可视窗口
     * 起始处的暖机 null 会更少（更准确），返回值语义不变。
     */
    private IndicatorVO fullIndicatorsCached(String code, String period) {
        LocalDate day = latestCompletedTradingDay();
        String expected = day.format(FMT);
        String key = RedisKeys.indicators(code, period, MAX_LIMIT, day.format(DAY_KEY_FMT));
        return cache.getOrLoad(key, RedisKeys.INDICATORS_TTL,
                new TypeReference<IndicatorVO>() {},
                () -> computeIndicators(fullSeriesCached(code, period)),
                vo -> endsAt(vo.getDates(), expected));
    }

    // ---------------------------------------------------------------
    // 全量序列缓存
    // ---------------------------------------------------------------

    private List<KlineVO> fullSeriesCached(String code, String period) {
        LocalDate day = latestCompletedTradingDay();
        String expected = day.format(FMT);
        String key = RedisKeys.kline(code, period, day.format(DAY_KEY_FMT));
        return cache.getOrLoad(key, RedisKeys.KLINE_TTL,
                new TypeReference<List<KlineVO>>() {},
                () -> loadFullSeries(code, period),
                list -> !list.isEmpty()
                        && expected.equals(list.get(list.size() - 1).getDate()));
    }

    /** 直查 DB：与旧版 getKline 完全相同的查询与排序逻辑，只是 limit 固定为上限 */
    private List<KlineVO> loadFullSeries(String code, String period) {
        Stock stock = stockService.getByCode(code);
        List<StockKline> rows = klineMapper.selectList(new LambdaQueryWrapper<StockKline>()
                .eq(StockKline::getStockId, stock.getId())
                .eq(StockKline::getPeriodType, period)
                .orderByDesc(StockKline::getTradeDate)
                .last("LIMIT " + MAX_LIMIT));
        return rows.stream()
                .sorted(Comparator.comparing(StockKline::getTradeDate))
                .map(k -> new KlineVO(k.getTradeDate().format(FMT),
                        k.getOpenPrice(), k.getHighPrice(), k.getLowPrice(),
                        k.getClosePrice(), k.getVolume(), k.getAmount()))
                .toList();
    }

    // ---------------------------------------------------------------
    // 指标计算（纯函数，输入即输出，与旧版逐项一致）
    // ---------------------------------------------------------------

    private IndicatorVO computeIndicators(List<KlineVO> klines) {
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

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static void requireValidPeriod(String period) {
        if (!PERIODS.contains(period)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                    "period 仅支持 day / week / month");
        }
    }

    private static int normalizeLimit(Integer limit) {
        return limit == null ? 250 : Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private static <T> List<T> tail(List<T> list, int n) {
        int from = Math.max(0, list.size() - n);
        return List.copyOf(list.subList(from, list.size()));
    }

    /** 按 [from, to) 对 IndicatorVO 的全部并行数组做同步切片 */
    private static IndicatorVO sliceIndicators(IndicatorVO full, int from, int to) {
        return IndicatorVO.builder()
                .dates(List.copyOf(full.getDates().subList(from, to)))
                .ma(sliceMap(full.getMa(), from, to))
                .macd(sliceMap(full.getMacd(), from, to))
                .kdj(sliceMap(full.getKdj(), from, to))
                .rsi(sliceMap(full.getRsi(), from, to))
                .build();
    }

    private static Map<String, List<BigDecimal>> sliceMap(Map<String, List<BigDecimal>> src, int from, int to) {
        Map<String, List<BigDecimal>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<BigDecimal>> e : src.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue().subList(from, to)));
        }
        return out;
    }

    /**
     * 该 K 线序列「应有」的最新交易日：昨天往前找到的第一个非周末日，
     * 与 MockMarketDataProvider.tradingDaysSince / lastTradingDays 的规则一致
     * （Mock 序列永远生成到昨天，不含今天）。
     *
     * 注意这只是缓存分片值：即使未来接入真实数据源、遇到节假日导致
     * 「应有日」当天并无新 K 线，效果也只是当天缓存不写入、请求直查 DB
     *（见 cacheable 判定），不会产生任何数据不一致。
     */
    static LocalDate latestCompletedTradingDay() {
        LocalDate d = LocalDate.now().minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** 周/月 K 的最后一根以「周期内末个交易日」落库，同样等于 latestCompletedTradingDay */
    private static boolean endsAt(List<String> dates, String expected) {
        return dates != null && !dates.isEmpty()
                && expected.equals(dates.get(dates.size() - 1));
    }
}
