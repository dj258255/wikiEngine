package com.wiki.engine.user.internal;

import com.wiki.engine.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 사용자 JPA 레포지토리.
 * internal 패키지에 위치하여 모듈 외부에서 직접 접근할 수 없다.
 * 같은 모듈의 UserService를 통해서만 접근 가능하다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** username으로 사용자 조회 (로그인 시 사용) */
    Optional<User> findByUsername(String username);

    /** username 중복 확인 (회원가입 시 사용) */
    boolean existsByUsername(String username);
}
