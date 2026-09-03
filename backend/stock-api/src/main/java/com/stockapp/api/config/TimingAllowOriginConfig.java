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
 * 【为什么需要这个】前端 utils/trafficStats.ts 靠浏览器 Resource Timing API
 * 的 transferSize 字段统计流量，但 Resource Timing 规范规定：跨域请求
 * 默认把 transferSize / encodedBodySize / decodedBodySize 这些"体积相关"
 * 字段清零，除非响应带 Timing-Allow-Origin 头显式允许——不是浏览器 bug，
 * 是刻意的隐私限制（防止第三方网站靠这些体积信息侧信道推断你在别的网站
 * 传输了什么内容）。前端部署在 Cloudflare Workers 域名，后端在 Railway，
 * 是跨域调用，中了这条规则，所以之前流量统计一直是 0 B。
 *
 * 跟 CorsConfig 里的 exposedHeaders（给 ETag 用的）不是一回事：那个是让
 * JS 代码能读到某个响应头的值（response.headers.get(...)），
 * Timing-Allow-Origin 是浏览器内部在生成 PerformanceResourceTiming 条目
 * 时自己检查的头，跟 JS 能不能读响应头无关，两者互不影响，不需要也不应该
 * 把 Timing-Allow-Origin 塞进 exposedHeaders 里。
 *
 * 【为什么直接用 *，不是回显具体 Origin】这个头暴露的信息只是"响应传输了
 * 多少字节、各阶段耗时多久"，不涉及响应内容本身，公开股票行情接口的这些
 * 元信息不敏感，用 * 最简单、以后新增前端域名/客户端也不用跟着改这里；
 * 如果之后有内容更敏感的接口需要更严格控制，再单独收窄那个接口的作用域。
 *
 * 【为什么覆盖 /api/*，不像 EtagConfig 那样只挑只读接口】ETag/304 只对
 * "可能没变化、值得省一次传输"的读接口有意义，但 Timing-Allow-Origin
 * 单纯是"允许看体积"，跟接口是读是写无关，所有 /api/* 接口都适用。
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
        reg.addUrlPatterns("/api/*");
        // 顺序不敏感——只是加一个响应头，跟 JWT 鉴权、ETag 生成谁先谁后都不冲突，
        // 放在默认顺序即可
        reg.setOrder(Ordered.LOWEST_PRECEDENCE);
        return reg;
    }
}
