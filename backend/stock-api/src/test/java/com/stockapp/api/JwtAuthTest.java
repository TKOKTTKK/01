package com.stockapp.api;

import com.stockapp.common.util.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** JWT 生成 / 校验 / 过期测试 */
class JwtAuthTest {

    private static final String SECRET = "unit-test-secret-key-1234567890-abcdefgh";

    @Test
    void generateAndParse_ok() {
        JwtUtil util = new JwtUtil(SECRET, 1);
        String token = util.generate(42L, "alice");
        assertEquals("42", util.parse(token).getSubject());
        assertEquals("alice", util.parse(token).get("username", String.class));
    }

    @Test
    void expiredToken_shouldThrow() {
        JwtUtil util = new JwtUtil(SECRET, 0); // 立即过期
        String token = util.generate(1L, "a");
        assertThrows(ExpiredJwtException.class, () -> util.parse(token));
    }

    @Test
    void tamperedToken_shouldThrow() {
        JwtUtil util = new JwtUtil(SECRET, 1);
        String token = util.generate(1L, "a") + "x";
        assertThrows(JwtException.class, () -> util.parse(token));
    }

    @Test
    void shortSecret_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("short", 1));
    }
}
