package com.stockapp.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 业务错误码 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    PARAM_ERROR(40000, "参数错误"),
    STOCK_NOT_FOUND(40001, "股票不存在"),
    USER_NOT_FOUND(40002, "用户不存在"),
    USERNAME_EXISTS(40003, "用户名已存在"),
    PASSWORD_WRONG(40004, "用户名或密码错误"),
    WATCHLIST_DUPLICATE(40005, "该股票已在自选中"),
    WATCHLIST_NOT_FOUND(40006, "自选记录不存在"),
    SIM_CASH_NOT_ENOUGH(40007, "可用资金不足"),
    SIM_POSITION_NOT_ENOUGH(40008, "卖出数量超过可卖数量"),
    SIM_QUANTITY_INVALID(40009, "委托数量不合法"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    TOKEN_EXPIRED(40101, "Token 已过期，请重新登录"),
    FORBIDDEN(40300, "无权限操作"),
    DB_ERROR(50001, "数据库异常"),
    REDIS_ERROR(50002, "缓存服务异常"),
    EXTERNAL_API_ERROR(50003, "外部行情服务异常"),
    SYSTEM_ERROR(50000, "系统繁忙，请稍后再试");

    private final int code;
    private final String message;
}
