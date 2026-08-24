package com.stockapp.api.controller;

import com.stockapp.api.security.CurrentUser;
import com.stockapp.common.result.Result;
import com.stockapp.common.vo.UserVO;
import com.stockapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 当前登录用户信息 */
    @GetMapping("/profile")
    public Result<UserVO> profile() {
        return Result.success(userService.getProfile(CurrentUser.id()));
    }
}
