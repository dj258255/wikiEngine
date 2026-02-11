package com.wiki.engine.user;

import com.wiki.engine.auth.AuthService;
import com.wiki.engine.auth.JwtTokenProvider;
import com.wiki.engine.auth.TokenBlacklist;
import com.wiki.engine.auth.UserPrincipal;
import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.user.dto.LoginRequest;
import com.wiki.engine.user.dto.SignupRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthService authService;

    // Security 필터 체인 의존성 (JwtAuthenticationFilter → JwtTokenProvider, TokenBlacklist)
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private TokenBlacklist tokenBlacklist;

    private static final String BASE = "/api/v1.0/auth";

    private static final ResponseCookie DUMMY_COOKIE = ResponseCookie.from("token", "jwt-token")
            .httpOnly(true).path("/").build();

    private User createTestUser() {
        return User.builder()
                .username("john1")
                .nickname("존도이")
                .password("$2a$10$hashed")
                .build();
    }

    // ========== POST /auth/signup ==========

    @Nested
    @DisplayName("POST /auth/signup")
    class Signup {

        @Test
        @DisplayName("[해피] 정상 회원가입 — 200 + Set-Cookie + userId/username 반환")
        void success() throws Exception {
            User user = createTestUser();
            given(userService.createUser("john1", "존도이", "password123")).willReturn(user);
            given(authService.issueToken(user.getId(), "john1")).willReturn(DUMMY_COOKIE);

            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new SignupRequest("john1", "존도이", "password123"))))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Set-Cookie"))
                    .andExpect(jsonPath("$.data.username").value("john1"));
        }

        @Test
        @DisplayName("[코너] 중복 username — 409 DUPLICATE_USERNAME")
        void duplicateUsername() throws Exception {
            given(userService.createUser("john1", "존도이", "password123"))
                    .willThrow(new BusinessException(ErrorCode.DUPLICATE_USERNAME));

            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new SignupRequest("john1", "존도이", "password123"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"));
        }

        @Test
        @DisplayName("[코너] 중복 nickname — 409 DUPLICATE_NICKNAME")
        void duplicateNickname() throws Exception {
            given(userService.createUser("john1", "존도이", "password123"))
                    .willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME));

            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new SignupRequest("john1", "존도이", "password123"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
        }

        @Test
        @DisplayName("[임계] username 빈값 — 400 Validation 실패")
        void blankUsername() throws Exception {
            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"","nickname":"존존","password":"password123"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[임계] username 4자 (min=5 미만) — 400 Validation 실패")
        void usernameTooShort() throws Exception {
            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"abcd","nickname":"존존","password":"password123"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[임계] username 정확히 5자 (min=5 경계) — 200 OK")
        void usernameExactlyMin() throws Exception {
            User user = User.builder().username("abc12").nickname("존존").password("$2a$10$h").build();
            given(userService.createUser("abc12", "존존", "password123")).willReturn(user);
            given(authService.issueToken(user.getId(), "abc12")).willReturn(DUMMY_COOKIE);

            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new SignupRequest("abc12", "존존", "password123"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("[임계] password 7자 (min=8 미만) — 400 Validation 실패")
        void shortPassword() throws Exception {
            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"john12","nickname":"존존","password":"1234567"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[임계] password 정확히 8자 (min=8 경계) — 200 OK")
        void passwordExactlyMin() throws Exception {
            User user = User.builder().username("john12").nickname("존존").password("$2a$10$h").build();
            given(userService.createUser("john12", "존존", "abcd1234")).willReturn(user);
            given(authService.issueToken(user.getId(), "john12")).willReturn(DUMMY_COOKIE);

            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new SignupRequest("john12", "존존", "abcd1234"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("[임계] nickname 1자 (min=2 미만) — 400 Validation 실패")
        void shortNickname() throws Exception {
            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"john12","nickname":"a","password":"password123"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[임계] nickname 정확히 2자 (min=2 경계) — 200 OK")
        void nicknameExactlyMin() throws Exception {
            User user = User.builder().username("john12").nickname("ab").password("$2a$10$h").build();
            given(userService.createUser("john12", "ab", "password123")).willReturn(user);
            given(authService.issueToken(user.getId(), "john12")).willReturn(DUMMY_COOKIE);

            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new SignupRequest("john12", "ab", "password123"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("[코너] 요청 body가 비어있으면 — 400")
        void emptyBody() throws Exception {
            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("[코너] 필드가 null이면 — 400")
        void nullFields() throws Exception {
            mockMvc.perform(post(BASE + "/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":null,"nickname":null,"password":null}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("[코너] Content-Type 없이 요청 — 에러 반환")
        void noContentType() throws Exception {
            mockMvc.perform(post(BASE + "/signup")
                            .content("""
                                    {"username":"john1","nickname":"존도이","password":"password123"}
                                    """))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected error status but got " + status;
                    });
        }
    }

    // ========== POST /auth/login ==========

    @Nested
    @DisplayName("POST /auth/login")
    class Login {

        @Test
        @DisplayName("[해피] 정상 로그인 — 200 + Set-Cookie + username 반환")
        void success() throws Exception {
            User user = createTestUser();
            given(userService.login("john1", "password123")).willReturn(user);
            given(authService.issueToken(user.getId(), "john1")).willReturn(DUMMY_COOKIE);

            mockMvc.perform(post(BASE + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new LoginRequest("john1", "password123"))))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Set-Cookie"))
                    .andExpect(jsonPath("$.data.username").value("john1"));
        }

        @Test
        @DisplayName("[코너] 잘못된 비밀번호 — 401 INVALID_CREDENTIALS")
        void wrongPassword() throws Exception {
            given(userService.login("john1", "wrong"))
                    .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

            mockMvc.perform(post(BASE + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new LoginRequest("john1", "wrong"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 사용자 — 401 INVALID_CREDENTIALS")
        void userNotFound() throws Exception {
            given(userService.login("unknown", "password123"))
                    .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

            mockMvc.perform(post(BASE + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new LoginRequest("unknown", "password123"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        @Test
        @DisplayName("[임계] username 빈값 — 400 Validation 실패")
        void blankUsername() throws Exception {
            mockMvc.perform(post(BASE + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"","password":"password123"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[임계] password 빈값 — 400 Validation 실패")
        void blankPassword() throws Exception {
            mockMvc.perform(post(BASE + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"john1","password":""}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[코너] body 없이 요청 — 400")
        void emptyBody() throws Exception {
            mockMvc.perform(post(BASE + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== POST /auth/logout ==========

    @Nested
    @DisplayName("POST /auth/logout")
    class Logout {

        @Test
        @DisplayName("[해피] 토큰이 있으면 블랙리스트 등록 + 쿠키 삭제")
        void withToken() throws Exception {
            ResponseCookie deleteCookie = ResponseCookie.from("token", "").maxAge(0).path("/").build();
            given(authService.revokeToken("my-token")).willReturn(deleteCookie);

            mockMvc.perform(post(BASE + "/logout")
                            .cookie(new jakarta.servlet.http.Cookie("token", "my-token")))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Set-Cookie"));

            verify(authService).revokeToken("my-token");
        }

        @Test
        @DisplayName("[코너] 토큰이 없어도 200 OK")
        void withoutToken() throws Exception {
            ResponseCookie deleteCookie = ResponseCookie.from("token", "").maxAge(0).path("/").build();
            given(authService.revokeToken(null)).willReturn(deleteCookie);

            mockMvc.perform(post(BASE + "/logout"))
                    .andExpect(status().isOk());
        }
    }

    // ========== GET /auth/check-username ==========

    @Nested
    @DisplayName("GET /auth/check-username")
    class CheckUsername {

        @Test
        @DisplayName("[해피] 사용 가능한 username — available: true")
        void available() throws Exception {
            given(userService.isUsernameAvailable("newuser")).willReturn(true);

            mockMvc.perform(get(BASE + "/check-username").param("value", "newuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.available").value(true));
        }

        @Test
        @DisplayName("[해피] 이미 존재하는 username — available: false")
        void taken() throws Exception {
            given(userService.isUsernameAvailable("john1")).willReturn(false);

            mockMvc.perform(get(BASE + "/check-username").param("value", "john1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.available").value(false));
        }

        @Test
        @DisplayName("[코너] value 파라미터 누락 — 에러 반환")
        void missingParam() throws Exception {
            mockMvc.perform(get(BASE + "/check-username"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected error status but got " + status;
                    });
        }
    }

    // ========== GET /auth/check-nickname ==========

    @Nested
    @DisplayName("GET /auth/check-nickname")
    class CheckNickname {

        @Test
        @DisplayName("[해피] 사용 가능한 nickname — available: true")
        void available() throws Exception {
            given(userService.isNicknameAvailable("새닉네임")).willReturn(true);

            mockMvc.perform(get(BASE + "/check-nickname").param("value", "새닉네임"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.available").value(true));
        }

        @Test
        @DisplayName("[해피] 이미 존재하는 nickname — available: false")
        void taken() throws Exception {
            given(userService.isNicknameAvailable("존")).willReturn(false);

            mockMvc.perform(get(BASE + "/check-nickname").param("value", "존"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.available").value(false));
        }

        @Test
        @DisplayName("[코너] value 파라미터 누락 — 에러 반환")
        void missingParam() throws Exception {
            mockMvc.perform(get(BASE + "/check-nickname"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected error status but got " + status;
                    });
        }
    }

    // ========== GET /auth/me ==========

    @Nested
    @DisplayName("GET /auth/me")
    class Me {

        @Test
        @DisplayName("[해피] 인증된 사용자 — userId + username 반환")
        void authenticated() throws Exception {
            UserPrincipal principal = new UserPrincipal(1L, "john1");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of()));

            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(1))
                    .andExpect(jsonPath("$.data.username").value("john1"));

            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("[코너] 미인증 사용자 — 401")
        void unauthenticated() throws Exception {
            SecurityContextHolder.clearContext();

            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
