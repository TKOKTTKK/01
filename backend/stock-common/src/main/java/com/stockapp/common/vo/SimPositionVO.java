package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 模拟持仓（含按实时行情计算的市值与盈亏） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimPositionVO {
    private Long stockId;
    private String code;
    private String name;
    private Long quantity;
    private Long availableQuantity;
    private BigDecimal avgCost;
    private BigDecimal price;        // 现价
    private BigDecimal marketValue;  // 市值
    private BigDecimal profit;       // 浮动盈亏
    private BigDecimal profitRate;   // 收益率 %
    private BigDecimal todayProfit;  // 今日盈亏
}
