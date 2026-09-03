package com.stockapp.common.util;

/**
 * A 股交易日分时点「分钟偏移（0~239）」与「HH:mm」互转。
 *
 * 交易时段固定为 9:30-11:30、13:00-15:00，各 120 分钟，合计 240 个整分钟点：
 *   偏移 0   = 09:30（上午开盘）
 *   偏移 119 = 11:29（上午最后一分钟）
 *   偏移 120 = 13:00（下午开盘）
 *   偏移 239 = 14:59（下午最后一分钟）
 *
 * 前端 utils/tradingMinuteOffset.ts 是这份规则的镜像实现——两边各自独立
 * 实现是因为分属 Java/TS 两个运行时、没有共享代码的构建产物，但换算规则
 * 本身极其稳定（A 股交易时段是硬编码的日历规则，不依赖任何外部配置），
 * 只要两边单测都覆盖了 0/119/120/239 这几个边界值，不容易出现不一致。
 */
public final class TradingMinuteOffset {

    private static final int MORNING_START_MIN = 9 * 60 + 30; // 09:30
    private static final int MORNING_END_MIN = 11 * 60 + 30;  // 11:30（开区间，最后一个点是 11:29）
    private static final int AFTERNOON_START_MIN = 13 * 60;   // 13:00
    private static final int MORNING_SESSION_LEN = MORNING_END_MIN - MORNING_START_MIN; // 120

    private TradingMinuteOffset() {
    }

    /** "HH:mm" -> 0~239 的分钟偏移；传入非交易时段的时间视为程序错误，直接抛异常。 */
    public static int toOffset(String hhmm) {
        int colon = hhmm.indexOf(':');
        int hour = Integer.parseInt(hhmm, 0, colon, 10);
        int minute = Integer.parseInt(hhmm, colon + 1, hhmm.length(), 10);
        int mins = hour * 60 + minute;
        if (mins >= MORNING_START_MIN && mins < MORNING_END_MIN) {
            return mins - MORNING_START_MIN;
        }
        if (mins >= AFTERNOON_START_MIN && mins < AFTERNOON_START_MIN + MORNING_SESSION_LEN) {
            return MORNING_SESSION_LEN + (mins - AFTERNOON_START_MIN);
        }
        throw new IllegalArgumentException("不在 A 股交易时段内的时间: " + hhmm);
    }

    /** 0~239 的分钟偏移 -> "HH:mm"，越界抛异常（调用方应保证 points 都在 [0,239]）。 */
    public static String toHHmm(int offset) {
        if (offset < 0 || offset >= 240) {
            throw new IllegalArgumentException("分钟偏移超出 [0,239]: " + offset);
        }
        int mins = offset < MORNING_SESSION_LEN
                ? MORNING_START_MIN + offset
                : AFTERNOON_START_MIN + (offset - MORNING_SESSION_LEN);
        return String.format("%02d:%02d", mins / 60, mins % 60);
    }
}
