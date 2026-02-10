package com.wiki.engine.auth;

import com.wiki.engine.auth.dto.AuthResponse;
import com.wiki.engine.auth.dto.LoginRequest;
import com.wiki.engine.auth.dto.SignupRequest;
import com.wiki.engine.user.User;
import com.wiki.engine.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 관련 REST API 컨트롤러.
 * 회원가입, 로그인, 로그아웃 엔드포인트를 제공한다.
 * JWT 토큰은 HttpOnly 쿠키로 전달하여 XSS 공격으로부터 보호한다.
 * WebConfig에서 "/api/v{version}" 접두사가 자동 적용되므로 실제 경로는 /api/v1.0/auth/** 형태가 된다.
 */
@RestController
@RequestMapping(path = "/auth", version = "1.0")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklist tokenBlacklist;
    private final PasswordEncoder passwordEncoder;

    @Value("${cookie.secure}")
    private boolean cookieSecure;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    /**
     * 회원가입 API.
     * 유저를 생성한 뒤 JWT 토큰을 HttpOnly 쿠키로 발급하여 바로 로그인 상태로 만든다.
     *
     * @param request 회원가입 요청 DTO (username, nickname, email, password)
     * @return 사용자 정보가 담긴 응답 + Set-Cookie 헤더
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        User user = userService.createUser(
                request.username(),
                request.nickname(),
                request.password()
        );

        String token = jwtTokenProvider.createToken(user.getId(), user.getUsername());
        ResponseCookie cookie = createTokenCookie(token, jwtExpiration);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(user.getUsername()));
    }

    /**
     * 로그인 API.
     * username으로 유저를 조회한 뒤 비밀번호를 BCrypt로 검증하고,
     * 성공 시 JWT 토큰을 HttpOnly 쿠키로 발급한다.
     *
     * @param request 로그인 요청 DTO (username, password)
     * @return 사용자 정보가 담긴 응답 + Set-Cookie 헤더
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = jwtTokenProvider.createToken(user.getId(), user.getUsername());
        ResponseCookie cookie = createTokenCookie(token, jwtExpiration);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(user.getUsername()));
    }

    /**
     * 로그아웃 API.
     * 쿠키에서 JWT 토큰을 추출하여 블랙리스트에 등록한 뒤,
     * Max-Age=0 쿠키를 전송하여 브라우저에서 삭제한다.
     *
     * @param token 쿠키에서 추출한 JWT 토큰 (없으면 null)
     * @return 200 OK + 쿠키 삭제 헤더
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "token", required = false) String token) {
        if (token != null) {
            tokenBlacklist.add(token);
        }
        ResponseCookie cookie = createTokenCookie("", 0);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    /**
     * 현재 로그인된 사용자 정보 조회 API.
     * 쿠키의 JWT 토큰을 검증하여 유저 정보를 반환한다.
     * 페이지 새로고침 시 세션 복원에 사용된다.
     *
     * @param token 쿠키에서 추출한 JWT 토큰 (없으면 null)
     * @return 사용자 정보 또는 401 Unauthorized
     */
    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(@CookieValue(name = "token", required = false) String token) {
        if (token == null || !jwtTokenProvider.validateToken(token) || tokenBlacklist.isBlacklisted(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String username = jwtTokenProvider.getUsername(token);
        return ResponseEntity.ok(new AuthResponse(username));
    }

    /**
     * JWT 토큰을 담은 HttpOnly 쿠키를 생성한다.
     *
     * @param token JWT 토큰 문자열 (삭제 시 빈 문자열)
     * @param maxAgeMs 쿠키 만료 시간 (밀리초, 삭제 시 0)
     * @return ResponseCookie 객체
     */
    private ResponseCookie createTokenCookie(String token, long maxAgeMs) {
        return ResponseCookie.from("token", token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeMs / 1000)
                .build();
    }
}
