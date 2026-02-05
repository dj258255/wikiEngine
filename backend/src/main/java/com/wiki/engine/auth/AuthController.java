package com.wiki.engine.auth;

import com.wiki.engine.auth.dto.LoginRequest;
import com.wiki.engine.auth.dto.SignupRequest;
import com.wiki.engine.auth.dto.TokenResponse;
import com.wiki.engine.user.User;
import com.wiki.engine.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 관련 REST API 컨트롤러.
 * 회원가입, 로그인, 로그아웃 엔드포인트를 제공한다.
 * WebConfig에서 "/api/v{version}" 접두사가 자동 적용되므로 실제 경로는 /api/v1.0/auth/** 형태가 된다.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklist tokenBlacklist;
    private final PasswordEncoder passwordEncoder;

    /**
     * 회원가입 API.
     * 유저를 생성한 뒤 JWT 토큰을 발급하여 바로 로그인 상태로 만든다.
     *
     * @param request 회원가입 요청 DTO (username, nickname, email, password)
     * @return JWT 토큰이 담긴 응답
     */
    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        User user = userService.createUser(
                request.username(),
                request.nickname(),
                request.password()
        );

        String token = jwtTokenProvider.createToken(user.getId(), user.getUsername());
        return ResponseEntity.ok(new TokenResponse(token));
    }

    /**
     * 로그인 API.
     * username으로 유저를 조회한 뒤 비밀번호를 BCrypt로 검증하고, 성공 시 JWT 토큰을 발급한다.
     *
     * @param request 로그인 요청 DTO (username, password)
     * @return JWT 토큰이 담긴 응답
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = jwtTokenProvider.createToken(user.getId(), user.getUsername());
        return ResponseEntity.ok(new TokenResponse(token));
    }

    /**
     * 로그아웃 API.
     * Authorization 헤더에서 JWT 토큰을 추출하여 블랙리스트에 등록한다.
     * 블랙리스트에 등록된 토큰은 만료 시간까지 사용이 차단된다.
     *
     * @param bearerToken "Bearer {token}" 형태의 Authorization 헤더 값
     * @return 200 OK
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String bearerToken) {
        // "Bearer " 접두사(7글자) 제거 후 토큰만 추출
        String token = bearerToken.substring(7);
        tokenBlacklist.add(token);
        return ResponseEntity.ok().build();
    }
}
