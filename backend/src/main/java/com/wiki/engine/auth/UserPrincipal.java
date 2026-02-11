package com.wiki.engine.auth;

/**
 * SecurityContext에 저장되는 인증된 사용자 정보.
 * JwtAuthenticationFilter에서 토큰 파싱 후 생성된다.
 *
 * @param userId 사용자 ID
 * @param username 사용자명
 */
public record UserPrincipal(Long userId, String username) {}
