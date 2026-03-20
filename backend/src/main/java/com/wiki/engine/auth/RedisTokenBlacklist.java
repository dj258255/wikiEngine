package com.wiki.engine.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 기반 JWT 토큰 블랙리스트.
 * 다중 인스턴스 환경에서 App 간 블랙리스트를 공유한다.
 *
 * - TTL: JWT의 남은 만료 시간 (전체 만료시간이 아님)
 *   → JWT가 자연 만료되면 Redis에서도 자동 제거
 * - Redis 장애 시: 보수적 정책 (모든 토큰 거부, 보안 우선)
 */
@Slf4j
@Component
public class RedisTokenBlacklist implements TokenBlacklist {

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    private static final String KEY_PREFIX = "blacklist:";

    public RedisTokenBlacklist(StringRedisTemplate redisTemplate, JwtTokenProvider jwtTokenProvider) {
        this.redisTemplate = redisTemplate;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public void add(String token) {
        long remainingMs = jwtTokenProvider.getExpirationTime(token);
        if (remainingMs <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue()
                    .set(KEY_PREFIX + token, "1", Duration.ofMillis(remainingMs));
        } catch (RedisConnectionFailureException e) {
            log.error("Redis 연결 실패 — 블랙리스트 등록 실패: {}", e.getMessage());
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + token));
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패 — 블랙리스트 확인 불가, 토큰 거부 (보안 우선): {}", e.getMessage());
            return true;
        }
    }
}
