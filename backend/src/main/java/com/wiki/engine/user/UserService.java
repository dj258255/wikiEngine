package com.wiki.engine.user;

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
            throw new IllegalArgumentException("Username already exists: " + username);
        }

        User user = User.builder()
                .username(username)
                .nickname(nickname)
                .password(passwordEncoder.encode(rawPassword))
                .build();

        return userRepository.save(user);
    }

    /** ID로 사용자를 조회한다. */
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    /** username으로 사용자를 조회한다 (로그인 시 사용). */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }
}
