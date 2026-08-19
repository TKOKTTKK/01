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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockDataInitializer implements ApplicationRunner {

    private static final int DAYS = 250;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StockMapper stockMapper;
    private final StockKlineMapper klineMapper;
    private final StockNewsMapper newsMapper;
    private final MarketDataProvider provider;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!provider.isMock()) {
            log.info("当前数据源为 {}，跳过 Mock 数据初始化", provider.name());
            return;
        }
        List<Stock> stocks = stockMapper.selectList(null);
        for (Stock stock : stocks) {
            initKline(stock);
            initNews(stock);
        }
        log.info("Mock 数据初始化完成，共 {} 只股票", stocks.size());
    }

    private void initKline(Stock stock) {
        Long exists = klineMapper.selectCount(new LambdaQueryWrapper<StockKline>()
                .eq(StockKline::getStockId, stock.getId())
                .eq(StockKline::getPeriodType, "day"));
        if (exists != null && exists > 0) {
            return;
        }
        List<KlineVO> daily = provider.getKline(stock.getCode(), DAYS);
        insertKlines(stock.getId(), "day", daily);
        insertKlines(stock.getId(), "week",
                aggregate(daily, d -> {
                    LocalDate date = LocalDate.parse(d.getDate(), FMT);
                    WeekFields wf = WeekFields.of(Locale.CHINA);
                    return date.getYear() + "-W" + date.get(wf.weekOfWeekBasedYear());
                }));
        insertKlines(stock.getId(), "month",
                aggregate(daily, d -> d.getDate().substring(0, 7)));
        log.info("已生成 {} ({}) K线: 日K {} 根", stock.getName(), stock.getCode(), daily.size());
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
