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
