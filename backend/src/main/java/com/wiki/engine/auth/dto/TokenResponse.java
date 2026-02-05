package com.wiki.engine.auth.dto;

/**
 * JWT 토큰 응답 DTO.
 * 로그인, 회원가입 성공 시 클라이언트에 반환된다.
 *
 * @param token JWT 액세스 토큰
 */
public record TokenResponse(
        String token
) {}
