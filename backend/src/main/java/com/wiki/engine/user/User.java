package com.wiki.engine.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 엔티티.
 * 회원가입한 사용자 및 더미 데이터(위키 데이터 임포트용 10만 명)를 저장한다.
 * 비밀번호는 BCrypt로 해싱하여 저장하며, 해시 결과는 60자 고정 길이이다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인용 사용자 아이디 (고유값, 최대 50자) */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** 표시용 닉네임 (고유값, 최대 50자) */
    @Column(nullable = false, unique = true, length = 50)
    private String nickname;

    /** BCrypt 해싱된 비밀번호 (60자 고정) */
    @Column(nullable = false, length = 60)
    private String password;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public User(String username, String nickname, String password) {
        this.username = username;
        this.nickname = nickname;
        this.password = password;
        this.createdAt = LocalDateTime.now();
    }

    /** 엔티티 수정 시 updatedAt을 자동으로 갱신한다. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
