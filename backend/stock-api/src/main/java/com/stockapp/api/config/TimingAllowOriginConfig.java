package com.stockapp.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 给 /api/stocks/* 加上 Timing-Allow-Origin 响应头，是 Protobuf+Gzip 灰度对比
 * 面板（DebugCachePanel.vue）能读到真实传输字节数的前提条件。
 *
 * 【背景】前端用浏览器 Resource Timing API（performance.getEntriesByType('resource')）
 * 读取每个请求实际的 encodedBodySize（gzip 压缩后、真正在网络上传输的字节数）来
 * 对比 JSON 和 Protobuf 两个接口的传输开销。但 Resource Timing 规范里，
 * 跨域请求默认把这些体积/时间字段全部清零（只留 startTime/duration 等少数
 * 字段），除非响应显式带上 Timing-Allow-Origin 告诉浏览器"这些数据允许暴露
 * 给发起请求的这个源"——这是防止恶意页面通过时序/体积旁路信息侧信道打探
 * 跨域资源内容的安全机制，不是 bug。
 *
 * 【为什么这里必须处理】这个项目生产环境前端在 Cloudflare、后端在 Railway
 * （见 frontend/.env.production 的 VITE_API_BASE_URL），是真跨域，不加这个头
 * 的话，线上灰度对比面板拿到的 encodedBodySize 会全部是 0，误导人以为测量
 * 失败——本地开发用 Vite dev server 代理到同源，不受影响，容易在本地测试时
 * 忽略这个问题，上线才发现。
 *
 * 只暴露体积/时间这类不含业务数据的元信息，用 "*" 通配符可以接受，不像
 * EtagConfig 里的 ETag 那样跟具体业务数据强相关，不需要收窄到具体域名。
 */
@Configuration
public class TimingAllowOriginConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> timingAllowOriginFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
                response.setHeader("Timing-Allow-Origin", "*");
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/stocks/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return reg;
    }
}
