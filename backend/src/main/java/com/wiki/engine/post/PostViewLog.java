package com.wiki.engine.post;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 게시글 조회 로그 엔티티.
 * 게시글 조회 이력을 기록하여 실시간 인기글 산정 등에 활용할 수 있다.
 * 현재는 엔티티만 정의되어 있으며, Repository/Service는 향후 구현 예정이다.
 *
 * 인덱스는 성능 최적화 단계에서 병목 확인 후 추가한다.
 */
@Entity
@Table(name = "post_view_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostViewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 조회된 게시글 ID */
    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** 조회 시각 */
    @Column(name = "viewed_at", nullable = false, updatable = false)
    private Instant viewedAt;

    public PostViewLog(Long postId) {
        this.postId = postId;
        this.viewedAt = Instant.now();
    }
}
