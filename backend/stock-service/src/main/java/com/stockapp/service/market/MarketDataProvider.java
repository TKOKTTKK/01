package com.stockapp.service.market;

import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.KlineVO;
import com.stockapp.common.vo.MarketIndexVO;
import com.stockapp.common.vo.NewsVO;
import com.stockapp.common.vo.QuoteVO;

import java.util.List;

/**
 * 行情数据源抽象。
 * 通过环境变量 MARKET_DATA_PROVIDER = mock | real 切换实现，
 * 业务代码只依赖本接口，不感知具体数据源。
 */
public interface MarketDataProvider {

    /** 数据源名称：mock / real */
    String name();

    /** 是否为模拟行情（UI 需要展示"模拟行情"标识） */
    boolean isMock();

    /** 实时行情 */
    QuoteVO getQuote(String code, String stockName);

    /** 日 K 历史（升序），days 为需要的天数 */
    List<KlineVO> getKline(String code, int days);

    /** 分时数据（当天） */
    IntradayVO getIntraday(String code);

    /** 市场指数：上证指数 / 深证成指 / 创业板指 */
    List<MarketIndexVO> getMarketIndex();

    /** 个股新闻 */
    List<NewsVO> getNews(String code, String stockName, int limit);
}
