package com.wiki.engine.user;

import com.wiki.engine.auth.AuthService;
import com.wiki.engine.auth.CurrentUser;
import com.wiki.engine.auth.UserPrincipal;
import com.wiki.engine.user.dto.AuthResponse;
import com.wiki.engine.user.dto.LoginRequest;
import com.wiki.engine.user.dto.SignupRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 사용자 인증 REST API 컨트롤러.
 * 회원가입, 로그인, 로그아웃 엔드포인트를 제공한다.
 * JWT 토큰 관리는 AuthService에 위임하고, 컨트롤러는 요청/응답 흐름만 담당한다.
 */
@RestController
@RequestMapping(path = "/auth", version = "1.0")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    /** 회원가입 후 JWT 토큰을 HttpOnly 쿠키로 발급한다. */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        User user = userService.createUser(request.username(), request.nickname(), request.password());
        ResponseCookie cookie = authService.issueToken(user.getId(), user.getUsername());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(user.getId(), user.getUsername()));
    }

    /** 로그인 인증 후 JWT 토큰을 HttpOnly 쿠키로 발급한다. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.login(request.username(), request.password());
        ResponseCookie cookie = authService.issueToken(user.getId(), user.getUsername());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(user.getId(), user.getUsername()));
    }

    /** 토큰을 블랙리스트에 등록하고 쿠키를 삭제한다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "token", required = false) String token) {
        ResponseCookie cookie = authService.revokeToken(token);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    /** 아이디 중복 확인. */
    @GetMapping("/check-username")
    public Map<String, Boolean> checkUsername(@RequestParam String value) {
        return Map.of("available", userService.isUsernameAvailable(value));
    }

    /** 닉네임 중복 확인. */
    @GetMapping("/check-nickname")
    public Map<String, Boolean> checkNickname(@RequestParam String value) {
        return Map.of("available", userService.isNicknameAvailable(value));
    }

    /** 현재 로그인된 사용자 정보를 반환한다. 인증 필요. */
    @GetMapping("/me")
    public AuthResponse me(@CurrentUser UserPrincipal currentUser) {
        return new AuthResponse(currentUser.userId(), currentUser.username());
    }
}
