package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟订单（第一版：按当前行情立即成交，status=FILLED） */
@Data
@TableName("simulation_order")
public class SimulationOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long stockId;
    private String side;
    private Long quantity;
    private BigDecimal price;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createdAt;
}
