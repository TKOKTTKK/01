package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟成交记录 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimTradeVO {
    private Long id;
    private String code;
    private String name;
    private String side;       // BUY / SELL
    private Long quantity;
    private BigDecimal price;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
