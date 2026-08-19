package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 股票 + 简要行情（列表/搜索/自选通用） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockVO {
    private Long id;
    private String code;
    private String name;
    private String market;
    private String industry;
    private BigDecimal price;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
}
