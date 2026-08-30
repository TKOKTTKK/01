package com.stockapp.api.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * 全量预取第一步：给股票/行情这批只读 GET 接口加上标准 HTTP 条件请求支持
 * （ETag + If-None-Match），本地缓存的数据如果没变，后端直接回 304，
 * 不重复传输 body——这是"全量静默预取"能做到"多次同步只传增量"的地基。
 *
 * 【为什么用 Spring 自带的 ShallowEtagHeaderFilter，不是自己维护一套版本号】
 * 1. 正确性更有保障：它是对"最终序列化出来的响应体"算哈希当 ETag，
 *    body 有任何变化（不管是价格变了、股票池增删了、字段格式改了）
 *    哈希天然就会变，不存在"忘记在某个改动路径里更新版本号"这种人为疏漏。
 *    如果自己维护版本号字段，每加一个会影响响应内容的改动，都要记得同步
 *    更新版本号，长期维护容易漏。
 * 2. 零业务代码侵入：不用碰 StockController/MarketController 一行代码，
 *    不用在每个 Service 方法里额外算"这批数据的版本号该是多少"。
 * 3. 是标准 HTTP 语义：浏览器 fetch/axios、以及后面前端要写的全量预取逻辑，
 *    只需要在请求头带 If-None-Match，标准协议，不需要额外定义/解析自定义
 *    的版本号响应字段。
 *
 * 【代价，说清楚不隐瞒】ShallowEtagHeaderFilter 是"shallow"（浅层）ETag：
 * 它必须等 Controller 把完整响应体都生成出来了，才能算哈希、决定要不要
 * 发 304——也就是说后端这边"生成数据"的计算量（查 Redis/查库/组装 JSON）
 * 一点没少，省下来的只是"最后这一步把 body 传输到客户端"的带宽。
 * 这批接口背后是 cache-aside 命中 Redis，生成成本本来就很低，这个代价
 * 完全划算；如果以后要给计算成本很高的接口（比如复杂聚合报表）加 ETag，
 * 就得考虑更"深层"的自定义方案（比如直接拿数据源的更新时间当 ETag，
 * 跳过重新生成 body 这一步），不能不假思索到处套用这个 filter。
 *
 * 【只扫读接口，不含写接口】只注册在 /api/stocks/* 和 /api/market/*——
 * 这两类都是纯 GET 只读查询（SecurityConfig 里也是 permitAll 的公开只读接口），
 * ETag/304 语义只对"内容会被重复读取、可能没变化"的场景有意义，
 * 用户自选/模拟交易这些个人化、会被修改的接口不需要也不应该套这个。
 */
@Configuration
public class EtagConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        FilterRegistrationBean<ShallowEtagHeaderFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/stocks/*", "/api/market/*");
        // 尽量靠前：要包住整条处理链（含 Spring Security 的过滤器链）生成完的
        // 响应体，才能在最外层拦下来算哈希、判断要不要改写成 304
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
