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

    /**
     * K 线全量序列: stock:kline:{code}:{period}:{day}
     *
     * key 里带 day（该周期序列应有的最新交易日，yyyyMMdd）是失效策略的核心：
     * Mock 数据每日增量追加一根，日期一滚动 key 就换新 → 旧 key 自然作废、
     * 新 key 首次访问回源，不依赖长 TTL 的过期时间，缓存永远不会滞后于「今日新 K 线」。
     * TTL 只用来兜底回收旧 key 的内存（26h > 一天，跨周末由预热任务续期）。
     */
    public static final String KLINE = "stock:kline:%s:%s:%s";
    public static final Duration KLINE_TTL = Duration.ofHours(26);

    /** 技术指标: stock:indicators:{code}:{period}:{limit}:{day}，day 语义同 KLINE */
    public static final String INDICATORS = "stock:indicators:%s:%s:%d:%s";
    public static final Duration INDICATORS_TTL = Duration.ofHours(26);

    public static String quote(String code)    { return QUOTE.formatted(code); }
    public static String info(String code)     { return INFO.formatted(code); }
    public static String intraday(String code) { return INTRADAY.formatted(code); }

    public static String kline(String code, String period, String day) {
        return KLINE.formatted(code, period, day);
    }

    public static String indicators(String code, String period, int limit, String day) {
        return INDICATORS.formatted(code, period, limit, day);
    }
}
