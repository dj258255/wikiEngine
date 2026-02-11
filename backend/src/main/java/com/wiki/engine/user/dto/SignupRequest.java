package com.wiki.engine.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 DTO.
 *
 * @param username 사용자 아이디 (5~20자, 영문/숫자)
 * @param nickname 닉네임 (2~12자)
 * @param password 비밀번호 (8~16자, 영문/숫자/특수문자 중 2가지 이상 조합)
 */
public record SignupRequest(
        @NotBlank @Size(min = 5, max = 20)
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)[a-zA-Z0-9]+$", message = "아이디는 영문과 숫자를 조합해야 합니다")
        String username,

        @NotBlank @Size(min = 2, max = 12)
        String nickname,

        @NotBlank @Size(min = 8, max = 16)
        @Pattern(
                regexp = "^(?:(?=.*[A-Za-z])(?=.*\\d)|(?=.*[A-Za-z])(?=.*[^A-Za-z0-9])|(?=.*\\d)(?=.*[^A-Za-z0-9])).+$",
                message = "비밀번호는 영문, 숫자, 특수문자 중 2가지 이상 조합해야 합니다"
        )
        String password
) {}
