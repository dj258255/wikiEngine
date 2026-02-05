package com.wiki.engine.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT 토큰 블랙리스트.
 * 로그아웃된 토큰을 Caffeine 로컬 캐시에 저장하여 재사용을 방지한다.
 *
 * - expireAfterWrite: JWT 만료 시간과 동일하게 설정하여 토큰 만료 후 자동 제거
 * - maximumSize: 최대 10만 개까지 저장 (초과 시 LRU 방식으로 제거)
 *
 * 단일 서버 환경에서 사용하며, 다중 서버 환경에서는 Redis 등 외부 저장소로 교체가 필요하다.
 */
@Component
public class TokenBlacklist {

    private final Cache<String, Boolean> blacklist;

    /**
     * JWT 만료 시간을 기준으로 Caffeine 캐시를 초기화한다.
     *
     * @param expiration JWT 토큰 만료 시간 (밀리초, application.yml의 jwt.expiration)
     */
    public TokenBlacklist(@Value("${jwt.expiration}") long expiration) {
        this.blacklist = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(expiration))
                .maximumSize(100_000)
                .build();
    }

    /**
     * 토큰을 블랙리스트에 등록한다.
     *
     * @param token 차단할 JWT 토큰
     */
    public void add(String token) {
        blacklist.put(token, true);
    }

    /**
     * 토큰이 블랙리스트에 등록되어 있는지 확인한다.
     *
     * @param token 확인할 JWT 토큰
     * @return 블랙리스트에 있으면 true
     */
    public boolean isBlacklisted(String token) {
        return blacklist.getIfPresent(token) != null;
    }
}
