package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("stock_news")
public class StockNews {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stockId;
    private String title;
    private String source;
    private String url;
    private String content;
    private LocalDateTime publishTime;
    private LocalDateTime createdAt;
}
