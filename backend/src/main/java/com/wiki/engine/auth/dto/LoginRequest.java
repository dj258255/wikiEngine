package com.wiki.engine.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 DTO.
 *
 * @param username 사용자 아이디
 * @param password 비밀번호 (평문, 서버에서 BCrypt로 비교)
 */
public record LoginRequest(
        @NotBlank
        String username,

        @NotBlank
        String password
) {}
