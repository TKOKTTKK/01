package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 单根 K 线 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KlineVO {
    private String date;          // yyyy-MM-dd
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
    private BigDecimal amount;
}
