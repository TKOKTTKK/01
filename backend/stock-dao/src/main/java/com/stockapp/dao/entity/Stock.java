package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("stock")
public class Stock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String market;
    private String industry;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
