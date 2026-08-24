package com.stockapp.api.controller;

import com.stockapp.common.dto.AuthRequest;
import com.stockapp.common.result.Result;
import com.stockapp.common.vo.TokenVO;
import com.stockapp.common.vo.UserVO;
import com.stockapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /** 注册 */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody AuthRequest req) {
        return Result.success(userService.register(req));
    }

    /** 登录，返回 JWT */
    @PostMapping("/login")
    public Result<TokenVO> login(@Valid @RequestBody AuthRequest req) {
        return Result.success(userService.login(req));
    }
}
