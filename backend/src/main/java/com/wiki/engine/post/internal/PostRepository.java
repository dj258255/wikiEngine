package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 게시글 JPA 레포지토리.
 * internal 패키지에 위치하여 모듈 외부에서 직접 접근할 수 없다.
 * 같은 모듈의 PostService를 통해서만 접근 가능하다.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 게시글 목록 — Deferred Join으로 OFFSET 최적화.
     * 내부 서브쿼리가 idx_posts_created_at 인덱스만 스캔하여 PK를 추출하고,
     * 외부 쿼리가 해당 PK로만 클러스터 인덱스를 조회한다.
     *
     * List 반환 + 명시적 LIMIT/OFFSET 파라미터를 사용한다.
     * Slice의 LIMIT+1 패턴은 서비스에서 처리한다.
     * (nativeQuery + Slice 반환 시 Spring Data 자동 LIMIT과 서브쿼리 LIMIT이 충돌하므로 회피)
     */
    @Query(value = """
        SELECT p.* FROM posts p
        INNER JOIN (
            SELECT id FROM posts ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
        ) AS tmp ON p.id = tmp.id
        ORDER BY p.created_at DESC
        """, nativeQuery = true)
    List<Post> findAllWithDeferredJoin(@Param("limit") int limit, @Param("offset") long offset);

    /** 카테고리별 게시글 목록 — Slice 반환으로 COUNT(*) 제거 */
    Slice<Post> findByCategoryIdOrderByCreatedAtDesc(Long categoryId, Pageable pageable);


    /**
     * Lucene 배치 인덱싱용 cursor 기반 조회.
     * ID 순으로 batchSize만큼 읽어온다 (OFFSET 없이 WHERE id > lastId).
     */
    @Query(value = "SELECT * FROM posts WHERE id > :lastId ORDER BY id ASC LIMIT :batchSize", nativeQuery = true)
    List<Post> findBatchAfterId(@Param("lastId") long lastId, @Param("batchSize") int batchSize);


    /** 조회수를 원자적으로 1 증가시킨다. */
    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /** 조회수를 지정된 값만큼 증가시킨다 (Redis 배치 flush용). */
    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + :delta WHERE p.id = :id")
    void incrementViewCountBy(@Param("id") Long id, @Param("delta") long delta);

    /** 좋아요 수를 원자적으로 1 증가시킨다. */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    /** 좋아요 수를 원자적으로 1 감소시킨다. (0 이하 방지) */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount - 1 WHERE p.id = :id AND p.likeCount > 0")
    void decrementLikeCount(@Param("id") Long id);
}
