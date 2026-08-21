package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟成交记录 */
@Data
@TableName("simulation_trade")
public class SimulationTrade {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long accountId;
    private Long stockId;
    private String side;
    private Long quantity;
    private BigDecimal price;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
