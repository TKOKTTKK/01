package com.stockapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Redis JSON 缓存工具：cache-aside，Redis 故障时自动降级为直查，
 * 保证行情功能在缓存不可用时依然工作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        return getOrLoad(key, ttl, type, loader, v -> true);
    }

    /**
     * 带「可缓存判定」的 cache-aside。
     *
     * cacheable 为 false 时只回源、不写缓存。K 线缓存需要它：
     * 跨天后 DB 可能还没补上新一根（补齐任务尚未跑），此时若把「旧序列」
     * 写进「新日期 key」，会在整个 TTL 内一直返回滞后数据；只查不写则
     * 每次都拿到 DB 当前真实状态，补齐完成后第一次访问自然转为可缓存。
     */
    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type,
                           Supplier<T> loader, Predicate<T> cacheable) {
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, type);
            }
        } catch (Exception e) {
            log.warn("Redis 读取失败, key={}, 降级直查: {}", key, e.getMessage());
        }
        T value = loader.get();
        try {
            if (cacheable.test(value)) {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            }
        } catch (Exception e) {
            log.warn("Redis 写入失败, key={}: {}", key, e.getMessage());
        }
        return value;
    }

    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除失败, key={}: {}", key, e.getMessage());
        }
    }
}
