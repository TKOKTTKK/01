package com.stockapp.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS：允许的域名通过环境变量 CORS_ALLOWED_ORIGINS 配置（逗号分隔）。
 * 开发环境默认放行 localhost 前端端口。
 *
 * exposedHeaders 里的 ETag：浏览器默认只暴露几个"安全列表"里的响应头给
 * 跨域请求的 JS 读（不含 ETag），不显式 expose 的话，前端 fetch/axios
 * 拿到的 Response 对象里 headers.get('ETag') 永远是 null——不是后端没发，
 * 是浏览器不让跨域 JS 看到。这里配的是 EtagConfig.java 里
 * ShallowEtagHeaderFilter 自动生成的那个 ETag 头，全量预取要靠前端读到它
 * 存进 IndexedDB，下次同步时带着当 If-None-Match 发回去。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("ETag"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
