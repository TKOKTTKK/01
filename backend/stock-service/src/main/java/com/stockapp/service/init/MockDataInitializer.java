package com.stockapp.service.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.vo.KlineVO;
import com.stockapp.common.vo.NewsVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.StockKline;
import com.stockapp.dao.entity.StockNews;
import com.stockapp.dao.mapper.StockKlineMapper;
import com.stockapp.dao.mapper.StockMapper;
import com.stockapp.dao.mapper.StockNewsMapper;
import com.stockapp.service.market.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 启动时初始化 Mock 数据（幂等）：
 * 1. 为每只股票生成 250 根日 K，并聚合出周 K / 月 K；
 * 2. 生成 Mock 新闻。
 * 已存在数据的股票自动跳过，不会产生重复（另有数据库唯一约束兜底）。
 *
 * v3.1：补齐逻辑抽出为公开的 {@link #catchUpAll()}，供 KlineWarmupJob
 * 在每日跨天后调用 —— 之前只有重启才会补新一根日 K，长期在线的实例
 * 会出现「分时昨收在走、DB K 线停在部署当天」的漂移；现在跨天由定时任务
 * 主动补齐，K 线缓存（按日期分 key）随之立即命中新数据。
 * {@code @Order(1)} 保证本初始化先于 KlineWarmupJob（@Order(2)）执行，
 * 预热时缓存里装的一定是补齐后的数据。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class MockDataInitializer implements ApplicationRunner {

    private static final int DAYS = 250;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StockMapper stockMapper;
    private final StockKlineMapper klineMapper;
    private final StockNewsMapper newsMapper;
    private final MarketDataProvider provider;

    /**
     * 事务注解必须同时放在 run() 与 catchUpAll() 上：
     * Spring 调 run() 走代理（本方法的事务生效），run() 内部对 catchUpAll()
     * 是自调用、不过代理，靠的是外层 run() 的事务；定时任务从外部调
     * catchUpAll() 时走代理，用的是 catchUpAll() 自己的事务。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        catchUpAll();
    }

    /**
     * 对全部股票补齐 K 线 + 初始化新闻（幂等，可重复调用）。
     *
     * synchronized：启动 Runner 与定时任务可能并发触发（极端时序下），
     * 补齐涉及「删旧周K再插」两步写入，串行化最省心；正常情况下锁无竞争。
     * 事务注解放在本方法上，外部经代理调用时生效；run() 内部自调用时
     * 整个补齐过程也在 Runner 线程串行完成，唯一约束兜底重复插入。
     */
    @Transactional
    public synchronized void catchUpAll() {
        if (!provider.isMock()) {
            log.info("当前数据源为 {}，跳过 Mock 数据初始化", provider.name());
            return;
        }
        List<Stock> stocks = stockMapper.selectList(null);
        for (Stock stock : stocks) {
            initKline(stock);
            initNews(stock);
        }
        log.info("Mock 数据初始化/补齐完成，共 {} 只股票", stocks.size());
    }

    /**
     * 初始化 / 补齐 K 线。
     *
     * 【为什么不是「有数据就跳过」】
     * 原实现只在首次启动生成一次，DB 里最后一根日 K 永远停在部署当天；
     * 而分时/昨收是按「今天的前一交易日」实时算的，随着时间推移两者会越差越远。
     * 现在改为：对比 DB 最后一根的日期与昨天，缺多少补多少（幂等，已存在的日期跳过），
     * 并重算受影响的周 K / 月 K（用 deleteThenInsert 覆盖当周/当月那一根）。
     */
    private void initKline(Stock stock) {
        LocalDate lastDay = latestTradeDate(stock.getId(), "day");
        List<KlineVO> daily = provider.getKline(stock.getCode(), DAYS);
        if (daily.isEmpty()) {
            return;
        }
        LocalDate newest = LocalDate.parse(daily.get(daily.size() - 1).getDate(), FMT);
        if (lastDay != null && !lastDay.isBefore(newest)) {
            return; // 已是最新，无需补齐
        }

        // 只插入 DB 中尚不存在的日期
        final LocalDate cutoff = lastDay;
        List<KlineVO> missing = daily.stream()
                .filter(k -> cutoff == null || LocalDate.parse(k.getDate(), FMT).isAfter(cutoff))
                .toList();
        insertKlines(stock.getId(), "day", missing);

        // 周 K / 月 K：重算受影响的周期（覆盖式写入，避免出现半截的周/月）
        List<KlineVO> weekly = aggregate(daily, d -> {
            LocalDate date = LocalDate.parse(d.getDate(), FMT);
            WeekFields wf = WeekFields.of(Locale.CHINA);
            return date.getYear() + "-W" + date.get(wf.weekOfWeekBasedYear());
        });
        List<KlineVO> monthly = aggregate(daily, d -> d.getDate().substring(0, 7));
        upsertKlines(stock.getId(), "week", affectedSince(weekly, cutoff));
        upsertKlines(stock.getId(), "month", affectedSince(monthly, cutoff));

        log.info("{} ({}) K线补齐: 新增日K {} 根（至 {}）",
                stock.getName(), stock.getCode(), missing.size(), newest);
    }

    /** 取该股票某周期在库中的最新交易日；无数据返回 null */
    private LocalDate latestTradeDate(Long stockId, String period) {
        StockKline last = klineMapper.selectOne(new LambdaQueryWrapper<StockKline>()
                .eq(StockKline::getStockId, stockId)
                .eq(StockKline::getPeriodType, period)
                .orderByDesc(StockKline::getTradeDate)
                .last("LIMIT 1"));
        return last == null ? null : last.getTradeDate();
    }

    /** 截取「受本次补齐影响」的周期：cutoff 当周/当月及之后的都要重算 */
    private List<KlineVO> affectedSince(List<KlineVO> periods, LocalDate cutoff) {
        if (cutoff == null) {
            return periods;
        }
        return periods.stream()
                .filter(k -> !LocalDate.parse(k.getDate(), FMT).isBefore(cutoff))
                .toList();
    }

    /** 覆盖式写入：先删同 (stock_id, period_type, trade_date) 再插，避免唯一约束冲突 */
    private void upsertKlines(Long stockId, String period, List<KlineVO> klines) {
        for (KlineVO k : klines) {
            LocalDate date = LocalDate.parse(k.getDate(), FMT);
            klineMapper.delete(new LambdaQueryWrapper<StockKline>()
                    .eq(StockKline::getStockId, stockId)
                    .eq(StockKline::getPeriodType, period)
                    .eq(StockKline::getTradeDate, date));
        }
        insertKlines(stockId, period, klines);
    }

    /** 将日 K 按 key 聚合为周/月 K（open=首日开，close=末日收，high/low 取极值） */
    private List<KlineVO> aggregate(List<KlineVO> daily, Function<KlineVO, String> keyFn) {
        Map<String, List<KlineVO>> groups = new LinkedHashMap<>();
        for (KlineVO k : daily) {
            groups.computeIfAbsent(keyFn.apply(k), x -> new ArrayList<>()).add(k);
        }
        List<KlineVO> out = new ArrayList<>(groups.size());
        for (List<KlineVO> g : groups.values()) {
            KlineVO first = g.get(0);
            KlineVO last = g.get(g.size() - 1);
            BigDecimal high = g.stream().map(KlineVO::getHigh).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low = g.stream().map(KlineVO::getLow).min(BigDecimal::compareTo).orElseThrow();
            long volume = g.stream().mapToLong(KlineVO::getVolume).sum();
            BigDecimal amount = g.stream().map(KlineVO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // 周期以末日日期落库，保证唯一约束 (stock_id, period_type, trade_date) 生效
            out.add(new KlineVO(last.getDate(), first.getOpen(), high, low,
                    last.getClose(), volume, amount));
        }
        return out;
    }

    private void insertKlines(Long stockId, String period, List<KlineVO> klines) {
        for (KlineVO k : klines) {
            StockKline row = new StockKline();
            row.setStockId(stockId);
            row.setPeriodType(period);
            row.setTradeDate(LocalDate.parse(k.getDate(), FMT));
            row.setOpenPrice(k.getOpen());
            row.setHighPrice(k.getHigh());
            row.setLowPrice(k.getLow());
            row.setClosePrice(k.getClose());
            row.setVolume(k.getVolume());
            row.setAmount(k.getAmount());
            row.setCreatedAt(LocalDateTime.now());
            klineMapper.insert(row);
        }
    }

    private void initNews(Stock stock) {
        Long exists = newsMapper.selectCount(new LambdaQueryWrapper<StockNews>()
                .eq(StockNews::getStockId, stock.getId()));
        if (exists != null && exists > 0) {
            return;
        }
        List<NewsVO> news = provider.getNews(stock.getCode(), stock.getName(), 8);
        for (NewsVO n : news) {
            StockNews row = new StockNews();
            row.setStockId(stock.getId());
            row.setTitle(n.getTitle());
            row.setSource(n.getSource());
            row.setUrl(n.getUrl());
            row.setContent(n.getContent());
            row.setPublishTime(n.getPublishTime());
            row.setCreatedAt(LocalDateTime.now());
            newsMapper.insert(row);
        }
    }
}
