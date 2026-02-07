package com.wiki.engine.post;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 게시글 엔티티.
 * 위키 데이터 및 사용자 작성 게시글을 모두 저장하는 핵심 테이블이다.
 * 약 2,700만 건의 위키피디아 데이터가 이 테이블에 임포트된다.
 *
 * 인덱스는 성능 최적화 단계에서 병목 확인 후 추가한다.
 */
@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 게시글 제목 (위키 문서 제목 포함, 최대 512자) */
    @Column(nullable = false, length = 512)
    private String title;

    /** 게시글 본문 (위키 문서 본문 포함, LONGTEXT로 대용량 지원) */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 작성자 ID (User 엔티티 FK, Modulith 구조상 직접 연관관계 대신 ID 참조) */
    @Column(name = "author_id", nullable = false)
    private Long authorId;

    /** 카테고리 ID (Category 엔티티 FK, nullable - 미분류 가능) */
    @Column(name = "category_id")
    private Long categoryId;

    /** 조회수 (DB 레벨 원자적 UPDATE로 동시성 관리) */
    @Column(name = "view_count", nullable = false)
    private Long viewCount = 0L;

    /** 좋아요 수 (INSERT IGNORE + 원자적 UPDATE로 동시성 관리) */
    @Column(name = "like_count", nullable = false)
    private Long likeCount = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Builder
    public Post(String title, String content, Long authorId, Long categoryId) {
        this.title = title;
        this.content = content;
        this.authorId = authorId;
        this.categoryId = categoryId;
        this.viewCount = 0L;
        this.likeCount = 0L;
        this.createdAt = Instant.now();
    }

    /** 게시글 제목과 본문을 수정한다. */
    public void update(String title, String content) {
        this.title = title;
        this.content = content;
        this.updatedAt = Instant.now();
    }

    /** 조회수 증가 (엔티티 레벨, 실제로는 Repository의 원자적 쿼리 사용 권장) */
    public void incrementViewCount() {
        this.viewCount++;
    }

    /** 좋아요 수 증가 */
    public void incrementLikeCount() {
        this.likeCount++;
    }

    /** 좋아요 수 감소 (0 이하로 내려가지 않음) */
    public void decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }
}
