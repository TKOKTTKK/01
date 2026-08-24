package com.stockapp.service;

import com.stockapp.common.dto.AuthRequest;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.util.JwtUtil;
import com.stockapp.common.vo.TokenVO;
import com.stockapp.dao.entity.User;
import com.stockapp.dao.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 注册 / 登录测试（Mock DAO，无需数据库） */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    private UserService userService;

    private final JwtUtil jwtUtil =
            new JwtUtil("unit-test-secret-key-1234567890-abcdefgh", 1);

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, jwtUtil);
    }

    private AuthRequest req(String u, String p) {
        AuthRequest r = new AuthRequest();
        r.setUsername(u);
        r.setPassword(p);
        return r;
    }

    @Test
    void register_shouldEncryptPasswordWithBCrypt() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            // 密码必须是 BCrypt，禁止明文
            assertTrue(u.getPassword().startsWith("$2"));
            assertTrue(new BCryptPasswordEncoder().matches("pass123456", u.getPassword()));
            return 1;
        });
        var vo = userService.register(req("alice", "pass123456"));
        assertEquals("alice", vo.getUsername());
        verify(userMapper).insert(any(User.class));
    }

    private static void assertTrue(boolean b) {
        org.junit.jupiter.api.Assertions.assertTrue(b);
    }

    @Test
    void register_duplicateUsername_shouldFail() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        BizException e = assertThrows(BizException.class,
                () -> userService.register(req("alice", "pass123456")));
        assertEquals(ErrorCode.USERNAME_EXISTS.getCode(), e.getCode());
    }

    @Test
    void login_success_shouldReturnParsableJwt() {
        User u = new User();
        u.setId(9L);
        u.setUsername("bob");
        u.setPassword(new BCryptPasswordEncoder().encode("secret66"));
        when(userMapper.selectOne(any())).thenReturn(u);

        TokenVO token = userService.login(req("bob", "secret66"));
        assertNotNull(token.getToken());
        assertEquals("9", jwtUtil.parse(token.getToken()).getSubject());
    }

    @Test
    void login_wrongPassword_shouldFail() {
        User u = new User();
        u.setId(9L);
        u.setUsername("bob");
        u.setPassword(new BCryptPasswordEncoder().encode("secret66"));
        when(userMapper.selectOne(any())).thenReturn(u);
        BizException e = assertThrows(BizException.class,
                () -> userService.login(req("bob", "wrong-pass")));
        assertEquals(ErrorCode.PASSWORD_WRONG.getCode(), e.getCode());
    }
}
