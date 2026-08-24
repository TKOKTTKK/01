package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 分时图单点 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntradayPointVO {
    private String time;          // HH:mm
    private BigDecimal price;
    private BigDecimal avgPrice;
    private Long volume;
    private BigDecimal amount;
}
