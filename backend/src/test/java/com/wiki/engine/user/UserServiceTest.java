package com.wiki.engine.user;

import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.user.internal.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    // ========== createUser ==========

    @Nested
    @DisplayName("createUser")
    class CreateUser {

        @Test
        @DisplayName("정상적으로 사용자를 생성한다")
        void success() {
            given(userRepository.existsByUsername("john")).willReturn(false);
            given(userRepository.existsByNickname("존")).willReturn(false);
            given(passwordEncoder.encode("password123")).willReturn("$2a$10$hashed");
            given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

            User user = userService.createUser("john", "존", "password123");

            assertThat(user.getUsername()).isEqualTo("john");
            assertThat(user.getNickname()).isEqualTo("존");
            assertThat(user.getPassword()).isEqualTo("$2a$10$hashed");
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("중복된 username이면 DUPLICATE_USERNAME 예외를 던진다")
        void duplicateUsername() {
            given(userRepository.existsByUsername("john")).willReturn(true);

            assertThatThrownBy(() -> userService.createUser("john", "존", "password123"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_USERNAME));

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("중복된 nickname이면 DUPLICATE_NICKNAME 예외를 던진다")
        void duplicateNickname() {
            given(userRepository.existsByUsername("john")).willReturn(false);
            given(userRepository.existsByNickname("존")).willReturn(true);

            assertThatThrownBy(() -> userService.createUser("john", "존", "password123"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_NICKNAME));

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("비밀번호를 평문 그대로 저장하지 않는다")
        void passwordIsEncoded() {
            given(userRepository.existsByUsername("john")).willReturn(false);
            given(userRepository.existsByNickname("존")).willReturn(false);
            given(passwordEncoder.encode("password123")).willReturn("$2a$10$encoded");
            given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

            User user = userService.createUser("john", "존", "password123");

            assertThat(user.getPassword()).isNotEqualTo("password123");
            assertThat(user.getPassword()).isEqualTo("$2a$10$encoded");
            verify(passwordEncoder).encode("password123");
        }
    }

    // ========== login ==========

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("올바른 자격증명으로 로그인에 성공한다")
        void success() {
            User user = User.builder()
                    .username("john")
                    .nickname("존")
                    .password("$2a$10$hashed")
                    .build();
            given(userRepository.findByUsername("john")).willReturn(Optional.of(user));
            given(passwordEncoder.matches("password123", "$2a$10$hashed")).willReturn(true);

            User result = userService.login("john", "password123");

            assertThat(result.getUsername()).isEqualTo("john");
        }

        @Test
        @DisplayName("존재하지 않는 username이면 INVALID_CREDENTIALS 예외를 던진다")
        void userNotFound() {
            given(userRepository.findByUsername("unknown")).willReturn(Optional.empty());

            assertThatThrownBy(() -> userService.login("unknown", "password123"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        }

        @Test
        @DisplayName("비밀번호가 틀리면 INVALID_CREDENTIALS 예외를 던진다")
        void wrongPassword() {
            User user = User.builder()
                    .username("john")
                    .nickname("존")
                    .password("$2a$10$hashed")
                    .build();
            given(userRepository.findByUsername("john")).willReturn(Optional.of(user));
            given(passwordEncoder.matches("wrong", "$2a$10$hashed")).willReturn(false);

            assertThatThrownBy(() -> userService.login("john", "wrong"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        }

        @Test
        @DisplayName("사용자 없음과 비밀번호 틀림이 동일한 에러코드를 반환한다 (정보 노출 방지)")
        void sameErrorForNotFoundAndWrongPassword() {
            given(userRepository.findByUsername("unknown")).willReturn(Optional.empty());

            assertThatThrownBy(() -> userService.login("unknown", "any"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

            // 비밀번호 틀림
            User user = User.builder().username("john").nickname("존").password("$2a$10$hashed").build();
            given(userRepository.findByUsername("john")).willReturn(Optional.of(user));
            given(passwordEncoder.matches("wrong", "$2a$10$hashed")).willReturn(false);

            assertThatThrownBy(() -> userService.login("john", "wrong"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        }
    }

    // ========== isUsernameAvailable ==========

    @Nested
    @DisplayName("isUsernameAvailable")
    class IsUsernameAvailable {

        @Test
        @DisplayName("사용 가능한 username이면 true를 반환한다")
        void available() {
            given(userRepository.existsByUsername("newuser")).willReturn(false);
            assertThat(userService.isUsernameAvailable("newuser")).isTrue();
        }

        @Test
        @DisplayName("이미 존재하는 username이면 false를 반환한다")
        void taken() {
            given(userRepository.existsByUsername("john")).willReturn(true);
            assertThat(userService.isUsernameAvailable("john")).isFalse();
        }
    }

    // ========== isNicknameAvailable ==========

    @Nested
    @DisplayName("isNicknameAvailable")
    class IsNicknameAvailable {

        @Test
        @DisplayName("사용 가능한 nickname이면 true를 반환한다")
        void available() {
            given(userRepository.existsByNickname("새닉네임")).willReturn(false);
            assertThat(userService.isNicknameAvailable("새닉네임")).isTrue();
        }

        @Test
        @DisplayName("이미 존재하는 nickname이면 false를 반환한다")
        void taken() {
            given(userRepository.existsByNickname("존")).willReturn(true);
            assertThat(userService.isNicknameAvailable("존")).isFalse();
        }
    }

    // ========== findById ==========

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("존재하는 사용자를 조회한다")
        void found() {
            User user = User.builder().username("john").nickname("존").password("hashed").build();
            given(userRepository.findById(1L)).willReturn(Optional.of(user));

            Optional<User> result = userService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getUsername()).isEqualTo("john");
        }

        @Test
        @DisplayName("존재하지 않는 ID면 empty를 반환한다")
        void notFound() {
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            assertThat(userService.findById(999L)).isEmpty();
        }
    }

    // ========== findByUsername ==========

    @Nested
    @DisplayName("findByUsername")
    class FindByUsername {

        @Test
        @DisplayName("존재하는 username으로 조회한다")
        void found() {
            User user = User.builder().username("john").nickname("존").password("hashed").build();
            given(userRepository.findByUsername("john")).willReturn(Optional.of(user));

            Optional<User> result = userService.findByUsername("john");

            assertThat(result).isPresent();
            assertThat(result.get().getUsername()).isEqualTo("john");
        }

        @Test
        @DisplayName("존재하지 않는 username이면 empty를 반환한다")
        void notFound() {
            given(userRepository.findByUsername("nobody")).willReturn(Optional.empty());

            assertThat(userService.findByUsername("nobody")).isEmpty();
        }
    }
}
