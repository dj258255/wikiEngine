package com.wiki.engine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 DTO.
 *
 * @param username 사용자 아이디 (3~50자)
 * @param nickname 닉네임 (2~50자)
 * @param password 비밀번호 (6~100자, 서버에서 BCrypt로 해싱)
 */
public record SignupRequest(
        @NotBlank @Size(min = 3, max = 50)
        String username,

        @NotBlank @Size(min = 2, max = 50)
        String nickname,

        @NotBlank @Size(min = 6, max = 100)
        String password
) {}
