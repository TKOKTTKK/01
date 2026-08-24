package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 实时行情 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteVO {
    private String code;
    private String name;
    private BigDecimal price;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal preClose;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
    private Long volume;          // 成交量（手）
    private BigDecimal amount;    // 成交额（元）
    private LocalDateTime tradeTime;
    private Boolean mock;         // 是否为模拟行情
}
