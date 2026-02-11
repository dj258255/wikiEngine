package com.wiki.engine.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * 인증 인프라 서비스.
 * JWT 토큰 발급/검증, 쿠키 생성, 토큰 블랙리스트를 캡슐화한다.
 * 컨트롤러가 JWT 구현 디테일을 알 필요 없도록 고수준 메서드만 노출한다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklist tokenBlacklist;

    @Value("${cookie.secure}")
    private boolean cookieSecure;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    /**
     * 사용자에 대한 JWT 토큰을 발급하고 HttpOnly 쿠키로 생성한다.
     *
     * @param userId 사용자 ID
     * @param username 사용자명
     * @return JWT 토큰이 담긴 Set-Cookie 헤더 값
     */
    public ResponseCookie issueToken(Long userId, String username) {
        String token = jwtTokenProvider.createToken(userId, username);
        return buildCookie(token, jwtExpiration);
    }

    /**
     * 토큰을 블랙리스트에 등록하고 쿠키를 삭제하는 Set-Cookie를 생성한다.
     *
     * @param token JWT 토큰 (null이면 블랙리스트 등록 생략)
     * @return Max-Age=0인 쿠키 삭제용 Set-Cookie 헤더 값
     */
    public ResponseCookie revokeToken(String token) {
        if (token != null) {
            tokenBlacklist.add(token);
        }
        return buildCookie("", 0);
    }

    private ResponseCookie buildCookie(String token, long maxAgeMs) {
        return ResponseCookie.from("token", token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeMs / 1000)
                .build();
    }
}
