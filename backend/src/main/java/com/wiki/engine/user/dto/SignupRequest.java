package com.wiki.engine.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 DTO.
 *
 * @param username 사용자 아이디 (5~20자)
 * @param nickname 닉네임 (2~12자)
 * @param password 비밀번호 (8~16자, 서버에서 BCrypt로 해싱)
 */
public record SignupRequest(
        @NotBlank @Size(min = 5, max = 20)
        String username,

        @NotBlank @Size(min = 2, max = 12)
        String nickname,

        @NotBlank @Size(min = 8, max = 16)
        String password
) {}
