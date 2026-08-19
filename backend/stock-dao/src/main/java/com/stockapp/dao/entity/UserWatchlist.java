package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_watchlist")
public class UserWatchlist {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long stockId;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
