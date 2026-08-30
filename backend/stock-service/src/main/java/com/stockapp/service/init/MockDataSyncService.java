package com.stockapp.service.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.vo.KlineVO;
import com.stockapp.common.vo.NewsVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.entity.StockKline;
import com.stockapp.dao.entity.StockNews;
import com.stockapp.dao.mapper.StockKlineMapper;
import com.stockapp.dao.mapper.StockNewsMapper;
import com.stockapp.service.market.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
 * 单只股票的 Mock 数据补齐：K 线（日/周/月）+ 新闻。
 *
 * 【事务边界为什么收敛到"一只股票"】原来 MockDataInitializer 把整个
 * 股票池的补齐放在一个大事务里，逐行 insert：8 只股票时几千行、几秒钟
 * 的事，500 只股票（250+根K线 × 500）就是十几万行、十几万次串行 DB
 * 往返，跑不完就可能被打断（连接超时/部署平台健康检查超时杀进程）——
 * 一旦打断，整个事务回滚，这一批股票一行数据都留不下。表现出来就是
 * "老股票能打开、新股票 K 线全报错"：老股票是之前独立的一次成功提交，
 * 跟这次的大事务无关；新股票每次重启都要重新陪绑这个必然超时的大事务。
 *
 * 现在每只股票是独立一个事务（本方法上的 @Transactional，由外部
 * MockDataInitializer 循环调用——必须是外部调用才能走 Spring 代理生效），
 * 单只失败只影响它自己；插入也从逐行 insert 改成分片批量 INSERT
 * （见 StockKlineMapper#insertBatch / StockNewsMapper#insertBatch），
 * 大幅减少 DB 往返次数。
 */
@Service
@RequiredArgsConstructor
public class MockDataSyncService {

    private static final int DAYS = 250;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 单条 INSERT 携带的行数上限：10 列 × 500 行 = 5000 个绑定参数，远低于 PostgreSQL 65535 上限 */
    private static final int CHUNK_SIZE = 500;

    private final StockKlineMapper klineMapper;
    private final StockNewsMapper newsMapper;
    private final MarketDataProvider provider;

    /** 补齐一只股票的 K 线 + 新闻，幂等，可重复调用 */
    @Transactional
    public void syncStock(Stock stock) {
        initKline(stock);
        initNews(stock);
    }

    /**
     * 初始化 / 补齐 K 线。
     *
     * 【为什么不是「有数据就跳过」】分时/昨收是按「今天的前一交易日」实时算的，
     * DB 最后一根日 K 如果停在部署当天不动，两者会越差越远。现在改为：
     * 对比 DB 最后一根的日期与昨天，缺多少补多少（幂等，已存在的日期跳过），
     * 并重算受影响的周 K / 月 K（覆盖当周/当月那一根）。
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
        if (klines.isEmpty()) {
            return;
        }
        List<LocalDate> dates = klines.stream().map(k -> LocalDate.parse(k.getDate(), FMT)).toList();
        klineMapper.delete(new LambdaQueryWrapper<StockKline>()
                .eq(StockKline::getStockId, stockId)
                .eq(StockKline::getPeriodType, period)
                .in(StockKline::getTradeDate, dates));
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

    /** 批量插入：分片，避免单条 SQL 绑定参数顶到 PostgreSQL 上限 */
    private void insertKlines(Long stockId, String period, List<KlineVO> klines) {
        if (klines.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<StockKline> rows = new ArrayList<>(klines.size());
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
            row.setCreatedAt(now);
            rows.add(row);
        }
        for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
            klineMapper.insertBatch(rows.subList(from, Math.min(from + CHUNK_SIZE, rows.size())));
        }
    }

    private void initNews(Stock stock) {
        Long exists = newsMapper.selectCount(new LambdaQueryWrapper<StockNews>()
                .eq(StockNews::getStockId, stock.getId()));
        if (exists != null && exists > 0) {
            return;
        }
        List<NewsVO> news = provider.getNews(stock.getCode(), stock.getName(), 8);
        if (news.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<StockNews> rows = new ArrayList<>(news.size());
        for (NewsVO n : news) {
            StockNews row = new StockNews();
            row.setStockId(stock.getId());
            row.setTitle(n.getTitle());
            row.setSource(n.getSource());
            row.setUrl(n.getUrl());
            row.setContent(n.getContent());
            row.setPublishTime(n.getPublishTime());
            row.setCreatedAt(now);
            rows.add(row);
        }
        newsMapper.insertBatch(rows);
    }
}
