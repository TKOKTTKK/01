package com.stockapp.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 模拟交易下单请求 */
@Data
public class SimOrderRequest {
    @NotBlank(message = "股票代码不能为空")
    private String code;

    @NotBlank(message = "交易方向不能为空")
    private String side; // BUY / SELL

    @NotNull(message = "数量不能为空")
    @Positive(message = "数量必须大于 0")
    private Long quantity;
}
