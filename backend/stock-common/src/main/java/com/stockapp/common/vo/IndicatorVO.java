package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 技术指标（与 K 线一一对应，按时间升序） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorVO {
    private List<String> dates;
    /** ma5 / ma10 / ma20 / ma60 */
    private Map<String, List<BigDecimal>> ma;
    /** dif / dea / macd */
    private Map<String, List<BigDecimal>> macd;
    /** k / d / j */
    private Map<String, List<BigDecimal>> kdj;
    /** rsi6 / rsi12 / rsi24 */
    private Map<String, List<BigDecimal>> rsi;
}
