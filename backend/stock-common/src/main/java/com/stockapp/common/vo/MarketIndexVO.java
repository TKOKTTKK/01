package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 市场指数 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketIndexVO {
    private String code;
    private String name;
    private BigDecimal value;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
}
