package com.wiki.engine.auth;

/**
 * JWT 토큰 블랙리스트.
 * 로그아웃된 토큰을 저장하여 재사용을 방지한다.
 *
 * 구현체:
 * - RedisTokenBlacklist: Redis 기반 (다중 인스턴스 환경, Phase 13+)
 */
public interface TokenBlacklist {

    /**
     * 토큰을 블랙리스트에 등록한다.
     *
     * @param token 차단할 JWT 토큰
     */
    void add(String token);

    /**
     * 토큰이 블랙리스트에 등록되어 있는지 확인한다.
     *
     * @param token 확인할 JWT 토큰
     * @return 블랙리스트에 있으면 true
     */
    boolean isBlacklisted(String token);
}
