package com.wiki.engine.auth.dto;

/**
 * 인증 응답 DTO.
 * 로그인, 회원가입 성공 시 클라이언트에 반환된다.
 * JWT 토큰은 HttpOnly 쿠키로 전달되므로 body에는 사용자 정보만 포함한다.
 *
 * @param username 사용자명
 */
public record AuthResponse(
        String username
) {}
