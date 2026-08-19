package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("stock_quote")
public class StockQuote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stockId;
    private BigDecimal price;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal preClose;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
    private Long volume;
    private BigDecimal amount;
    private LocalDateTime tradeTime;
    private LocalDateTime createdAt;
}
