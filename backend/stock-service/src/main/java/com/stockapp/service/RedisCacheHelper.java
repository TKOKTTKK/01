package com.stockapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

    /**
     * 批量 cache-aside：一次 MGET 读全部 key，未命中的 code 并行回源计算，
     * 回源结果最后用一次 pipeline 写回 Redis。
     *
     * 【为什么需要这个】股票池扩大后，任何"每只股票单独一次 getOrLoad"的调用点
     * （行情列表、行情快照定时任务等）都会退化成 N 次串行 Redis 往返——8 只股票
     * 感觉不到，几千只就是几秒延迟。批量版本把 N 次网络往返压成 1 次读 + 1 次写，
     * 回源计算量不变但不再串行等待。
     *
     * @param codes  要查询的股票代码列表
     * @param keyFn  code -> Redis key
     * @param loader 未命中时的回源函数，入参 code
     */
    public <T> Map<String, T> getOrLoadBatch(List<String> codes, Function<String, String> keyFn,
                                              Duration ttl, TypeReference<T> type,
                                              Function<String, T> loader) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        List<String> keys = codes.stream().map(keyFn).toList();
        Map<String, String> rawByCode = new HashMap<>();
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values != null) {
                for (int i = 0; i < codes.size(); i++) {
                    String v = values.get(i);
                    if (v != null) {
                        rawByCode.put(codes.get(i), v);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Redis 批量读取失败，全部降级为回源计算: {}", e.getMessage());
        }

        Map<String, T> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            String raw = rawByCode.get(code);
            if (raw != null) {
                try {
                    result.put(code, objectMapper.readValue(raw, type));
                    continue;
                } catch (Exception e) {
                    log.warn("缓存反序列化失败, code={}, 视为未命中重新计算: {}", code, e.getMessage());
                }
            }
            missing.add(code);
        }

        if (!missing.isEmpty()) {
            // 回源多为内存计算或独立 IO，彼此无依赖，并行执行而不是逐个等待
            Map<String, T> loaded = missing.parallelStream()
                    .collect(Collectors.toMap(code -> code, loader::apply));
            result.putAll(loaded);
            writeBackPipelined(loaded, keyFn, ttl);
        }
        return result;
    }

    private <T> void writeBackPipelined(Map<String, T> loaded, Function<String, String> keyFn, Duration ttl) {
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                for (Map.Entry<String, T> e : loaded.entrySet()) {
                    try {
                        conn.setEx(keyFn.apply(e.getKey()), ttl.getSeconds(),
                                objectMapper.writeValueAsString(e.getValue()));
                    } catch (Exception ex) {
                        log.warn("批量写回序列化失败, code={}: {}", e.getKey(), ex.getMessage());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Redis 批量写回失败（不影响本次返回结果，只是下次仍会回源）: {}", e.getMessage());
        }
    }

    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除失败, key={}: {}", key, e.getMessage());
        }
    }
}
