package com.stockapp.api.exception;

import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.result.Result;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 全局异常处理：所有异常统一转换为 Result 格式 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.PARAM_ERROR.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), msg);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public Result<Void> handleBadRequest(Exception e) {
        log.info("参数错误: {}", e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR);
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public Result<Void> handleTokenExpired(ExpiredJwtException e) {
        return Result.error(ErrorCode.TOKEN_EXPIRED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.error(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), "接口不存在");
    }

    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDb(DataAccessException e) {
        log.error("数据库异常", e);
        return Result.error(ErrorCode.DB_ERROR);
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public Result<Void> handleRedis(RedisConnectionFailureException e) {
        log.error("Redis 异常: {}", e.getMessage());
        return Result.error(ErrorCode.REDIS_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}
