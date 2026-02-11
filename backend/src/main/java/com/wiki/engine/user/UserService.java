package com.wiki.engine.user;

import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.user.internal.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 사용자 비즈니스 로직 서비스.
 * 사용자 생성(회원가입) 및 조회 기능을 제공한다.
 * 비밀번호는 BCrypt PasswordEncoder로 해싱하여 저장한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 새 사용자를 생성한다.
     * username 중복 시 예외를 던진다.
     * rawPassword는 BCrypt로 해싱하여 저장한다.
     *
     * @param username 사용자 아이디
     * @param nickname 닉네임
     * @param rawPassword 평문 비밀번호 (BCrypt 해싱 후 저장)
     * @return 생성된 사용자 엔티티
     */
    @Transactional
    public User createUser(String username, String nickname, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        User user = User.builder()
                .username(username)
                .nickname(nickname)
                .password(passwordEncoder.encode(rawPassword))
                .build();

        return userRepository.save(user);
    }

    /** username 사용 가능 여부를 확인한다. */
    public boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username);
    }

    /** nickname 사용 가능 여부를 확인한다. */
    public boolean isNicknameAvailable(String nickname) {
        return !userRepository.existsByNickname(nickname);
    }

    /** ID로 사용자를 조회한다. */
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    /** username으로 사용자를 조회한다 (로그인 시 사용). */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * 사용자 로그인을 처리한다.
     * username으로 사용자를 조회하고 BCrypt 비밀번호를 검증한다.
     *
     * @param username 사용자 아이디
     * @param rawPassword 평문 비밀번호
     * @return 인증된 사용자 엔티티
     * @throws BusinessException INVALID_CREDENTIALS — 사용자 없음 또는 비밀번호 불일치
     */
    public User login(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        return user;
    }
}
