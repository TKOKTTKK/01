package com.stockapp.api.security;

import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 获取当前登录用户 ID */
public final class CurrentUser {

    private CurrentUser() {}

    public static Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
