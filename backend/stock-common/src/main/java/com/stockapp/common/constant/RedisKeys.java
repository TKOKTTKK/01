package com.stockapp.common.constant;

import java.time.Duration;

/** Redis Key 与 TTL 统一定义，禁止在业务代码中随意拼 Key */
public final class RedisKeys {

    private RedisKeys() {}

    /** 实时行情: stock:quote:{code} */
    public static final String QUOTE = "stock:quote:%s";
    public static final Duration QUOTE_TTL = Duration.ofSeconds(10);

    /** 股票信息: stock:info:{code} */
    public static final String INFO = "stock:info:%s";
    public static final Duration INFO_TTL = Duration.ofHours(6);

    /** 热门股票 */
    public static final String HOT = "stock:hot";
    public static final Duration HOT_TTL = Duration.ofMinutes(5);

    /** 市场指数 */
    public static final String MARKET_INDEX = "market:index";
    public static final Duration MARKET_INDEX_TTL = Duration.ofSeconds(10);

    /** 分时: stock:intraday:{code} */
    public static final String INTRADAY = "stock:intraday:%s";
    public static final Duration INTRADAY_TTL = Duration.ofSeconds(30);

    public static String quote(String code)    { return QUOTE.formatted(code); }
    public static String info(String code)     { return INFO.formatted(code); }
    public static String intraday(String code) { return INTRADAY.formatted(code); }
}
