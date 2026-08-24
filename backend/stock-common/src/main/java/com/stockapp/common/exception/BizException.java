package com.stockapp.common.exception;

import com.stockapp.common.result.ErrorCode;
import lombok.Getter;

/** 业务异常：由 GlobalExceptionHandler 统一转换为 Result */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
