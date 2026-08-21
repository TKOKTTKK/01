package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟资金流水（正为入账，负为出账） */
@Data
@TableName("simulation_cash_flow")
public class SimulationCashFlow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String type;
    private BigDecimal amount;
    private BigDecimal balance;
    private String description;
    private LocalDateTime createdAt;
}
