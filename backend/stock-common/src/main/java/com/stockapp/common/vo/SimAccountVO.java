package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 模拟账户总览 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimAccountVO {
    private BigDecimal totalAssets;         // 总资产 = 可用 + 冻结 + 持仓市值
    private BigDecimal availableCash;       // 可用资金
    private BigDecimal frozenCash;          // 冻结资金
    private BigDecimal positionMarketValue; // 持仓市值
    private BigDecimal todayProfit;         // 今日盈亏（按昨收估算）
    private BigDecimal totalProfit;         // 总收益 = 总资产 - 初始资金
    private BigDecimal totalProfitRate;     // 收益率 %
    private BigDecimal initialCash;         // 初始资金
    private boolean mock;                   // 模拟标识
}
