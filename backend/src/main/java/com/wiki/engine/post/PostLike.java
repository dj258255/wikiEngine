package com.wiki.engine.post;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 게시글 좋아요 엔티티.
 * 유저 1명이 게시글 1개에 대해 1번만 좋아요를 누를 수 있도록
 * (post_id, user_id) 조합에 UNIQUE 제약을 설정한다.
 *
 * 좋아요 동시성 처리:
 * - INSERT IGNORE를 사용하여 중복 좋아요 시 에러 없이 무시
 * - 좋아요 수는 Post 엔티티의 like_count에 반정규화하여 저장
 */
@Entity
@Table(name = "post_likes",
    uniqueConstraints = @UniqueConstraint(name = "uk_post_likes_post_user", columnNames = {"post_id", "user_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 좋아요 대상 게시글 ID */
    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** 좋아요를 누른 사용자 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PostLike(Long postId, Long userId) {
        this.postId = postId;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
    }
}
