package com.stockapp.api.config;

import com.stockapp.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** JWT Secret 必须来自环境变量（见 application.yml -> app.jwt.secret） */
    @Bean
    public JwtUtil jwtUtil(@Value("${app.jwt.secret}") String secret,
                           @Value("${app.jwt.expire-hours:24}") long expireHours) {
        return new JwtUtil(secret, expireHours);
    }
}
