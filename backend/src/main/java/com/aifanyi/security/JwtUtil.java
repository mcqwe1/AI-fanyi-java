package com.aifanyi.security;

import com.aifanyi.config.AifanyiProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析（jjwt 0.12.x API）。
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(AifanyiProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireMillis = props.getJwt().getExpireMinutes() * 60_000L;
    }

    public String generate(Long userId, String username) {
        return generate(userId, username, expireMillis);
    }

    /** 自定义有效期的签发（浏览器扩展等长效场景），复用同一密钥与校验链路。 */
    public String generate(Long userId, String username, long ttlMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验，失败返回 null。 */
    public Claims parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return jws.getPayload();
        } catch (Exception e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }
}
