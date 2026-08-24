package com.stockapp.service;

import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.KlineVO;
import com.stockapp.common.vo.MarketIndexVO;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.service.market.MockMarketDataProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mock 行情数据源测试 */
class MockMarketDataProviderTest {

    private final MockMarketDataProvider provider = new MockMarketDataProvider();

    @Test
    void kline_shouldBeDeterministicAndValid() {
        List<KlineVO> a = provider.getKline("600519", 250);
        List<KlineVO> b = provider.getKline("600519", 250);
        assertEquals(250, a.size());
        assertEquals(a.get(100).getClose(), b.get(100).getClose(), "同一日数据必须确定性一致");
        for (KlineVO k : a) {
            assertTrue(k.getHigh().compareTo(k.getLow()) >= 0);
            assertTrue(k.getHigh().compareTo(k.getOpen()) >= 0);
            assertTrue(k.getHigh().compareTo(k.getClose()) >= 0);
            assertTrue(k.getLow().compareTo(k.getOpen()) <= 0);
            assertTrue(k.getClose().signum() > 0);
            assertTrue(k.getVolume() > 0);
        }
    }

    @Test
    void quote_shouldBeConsistentWithIntraday() {
        QuoteVO q = provider.getQuote("000001", "平安银行");
        IntradayVO in = provider.getIntraday("000001");
        assertEquals(q.getPreClose(), in.getPreClose());
        assertEquals(Boolean.TRUE, q.getMock(), "Mock 数据必须带模拟标识");
        // 涨跌幅 = (price - preClose) / preClose * 100
        BigDecimal expect = q.getPrice().subtract(q.getPreClose())
                .multiply(BigDecimal.valueOf(100))
                .divide(q.getPreClose(), 3, java.math.RoundingMode.HALF_UP);
        assertEquals(0, q.getChangePercent().compareTo(expect));
    }

    /**
     * 回归测试：这就是「同一天收盘价随请求天数变化」那个 bug。
     * 修复前 getKline(code, 2) 和 getKline(code, 250) 对同一个日历日会算出
     * 完全不同的收盘价（因为随机游走步数 = days）；修复后必须完全一致。
     */
    @Test
    void kline_sameDayMustMatchAcrossDifferentDayCounts() {
        List<KlineVO> shortSeries = provider.getKline("600519", 2);
        List<KlineVO> longSeries = provider.getKline("600519", 250);

        KlineVO lastShort = shortSeries.get(shortSeries.size() - 1);
        KlineVO lastLong = longSeries.get(longSeries.size() - 1);
        assertEquals(lastLong.getDate(), lastShort.getDate(), "最后一根必须是同一天");
        assertEquals(0, lastLong.getClose().compareTo(lastShort.getClose()),
                "同一交易日的收盘价不能因请求天数不同而变化");
        assertEquals(0, lastLong.getOpen().compareTo(lastShort.getOpen()));
        assertEquals(0, lastLong.getHigh().compareTo(lastShort.getHigh()));
        assertEquals(0, lastLong.getLow().compareTo(lastShort.getLow()));

        // 倒数第二根同理
        KlineVO prevShort = shortSeries.get(0);
        KlineVO prevLong = longSeries.get(longSeries.size() - 2);
        assertEquals(prevLong.getDate(), prevShort.getDate());
        assertEquals(0, prevLong.getClose().compareTo(prevShort.getClose()));
    }

    /** 分时里的昨收必须等于 K 线序列中前一交易日的收盘价 */
    @Test
    void intradayPreClose_shouldMatchKlineHistory() {
        IntradayVO in = provider.getIntraday("600519");
        List<KlineVO> hist = provider.getKline("600519", 250);
        boolean matched = hist.stream()
                .anyMatch(k -> k.getClose().compareTo(in.getPreClose()) == 0);
        assertTrue(matched, "昨收必须来自同一份 K 线历史，而非独立生成");
    }

    @Test
    void marketIndex_shouldReturnThreeIndexes() {
        List<MarketIndexVO> idx = provider.getMarketIndex();
        assertEquals(3, idx.size());
        assertEquals("上证指数", idx.get(0).getName());
    }

    @Test
    void news_shouldContainStockName() {
        var news = provider.getNews("600519", "贵州茅台", 5);
        assertEquals(5, news.size());
        assertTrue(news.get(0).getTitle().contains("贵州茅台"));
    }
}
