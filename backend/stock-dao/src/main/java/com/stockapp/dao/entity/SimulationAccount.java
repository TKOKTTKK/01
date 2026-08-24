package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟交易账户 */
@Data
@TableName("simulation_account")
public class SimulationAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private BigDecimal initialCash;
    private BigDecimal availableCash;
    private BigDecimal frozenCash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
