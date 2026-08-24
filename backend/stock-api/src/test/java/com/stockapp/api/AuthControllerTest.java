package com.stockapp.api;

import com.stockapp.api.controller.AuthController;
import com.stockapp.api.exception.GlobalExceptionHandler;
import com.stockapp.common.dto.AuthRequest;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.TokenVO;
import com.stockapp.common.vo.UserVO;
import com.stockapp.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 认证 API 测试（standalone MockMvc，无需数据库） */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserService userService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void register_ok() throws Exception {
        when(userService.register(any(AuthRequest.class)))
                .thenReturn(UserVO.builder().id(1L).username("alice").build());
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pass123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void register_shortPassword_shouldReturnParamError() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void login_ok_shouldReturnToken() throws Exception {
        when(userService.login(any(AuthRequest.class)))
                .thenReturn(new TokenVO("jwt-token",
                        UserVO.builder().id(1L).username("alice").build()));
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pass123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("jwt-token"));
    }

    @Test
    void login_wrongPassword_shouldReturnBizError() throws Exception {
        when(userService.login(any(AuthRequest.class)))
                .thenThrow(new BizException(ErrorCode.PASSWORD_WRONG));
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"badpassword\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_WRONG.getCode()));
    }
}
