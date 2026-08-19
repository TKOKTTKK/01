package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.dto.AuthRequest;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.util.JwtUtil;
import com.stockapp.common.vo.TokenVO;
import com.stockapp.common.vo.UserVO;
import com.stockapp.dao.entity.User;
import com.stockapp.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 用户注册 / 登录 / 信息（密码 BCrypt，日志不打印任何敏感信息） */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public UserVO register(AuthRequest req) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername()));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册时兜底：依赖数据库唯一约束
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        log.info("用户注册成功: username={}", user.getUsername());
        return toVO(user);
    }

    public TokenVO login(AuthRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.info("登录失败: username={}", req.getUsername());
            throw new BizException(ErrorCode.PASSWORD_WRONG);
        }
        String token = jwtUtil.generate(user.getId(), user.getUsername());
        log.info("登录成功: username={}", user.getUsername());
        return new TokenVO(token, toVO(user));
    }

    public UserVO getProfile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return toVO(user);
    }

    private UserVO toVO(User user) {
        return UserVO.builder()
                .id(user.getId()).username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
