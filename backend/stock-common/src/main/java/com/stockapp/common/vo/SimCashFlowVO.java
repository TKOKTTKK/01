package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟资金流水 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimCashFlowVO {
    private Long id;
    private String type;
    private BigDecimal amount;
    private BigDecimal balance;
    private String description;
    private LocalDateTime createdAt;
}
