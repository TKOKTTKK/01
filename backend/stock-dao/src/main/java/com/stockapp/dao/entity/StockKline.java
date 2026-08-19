package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("stock_kline")
public class StockKline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stockId;
    /** day / week / month */
    private String periodType;
    private LocalDate tradeDate;
    private LocalDateTime tradeTime;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private Long volume;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
