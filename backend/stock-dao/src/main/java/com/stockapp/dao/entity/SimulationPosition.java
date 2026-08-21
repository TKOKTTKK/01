package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟持仓（account_id + stock_id 唯一） */
@Data
@TableName("simulation_position")
public class SimulationPosition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long stockId;
    private Long quantity;
    private Long availableQuantity;
    private BigDecimal avgCost;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
