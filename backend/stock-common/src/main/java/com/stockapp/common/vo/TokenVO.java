package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 登录返回 */
@Data
@AllArgsConstructor
public class TokenVO {
    private String token;
    private UserVO user;
}
