package com.stockapp.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** user 为 PostgreSQL 保留字，表名需加引号 */
@Data
@TableName("\"user\"")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 加密后的密码，禁止明文 */
    private String password;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
