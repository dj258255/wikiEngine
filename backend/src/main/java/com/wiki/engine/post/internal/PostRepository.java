package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 게시글 JPA 레포지토리.
 * internal 패키지에 위치하여 모듈 외부에서 직접 접근할 수 없다.
 * 같은 모듈의 PostService를 통해서만 접근 가능하다.
 *
 * 1단계에서는 의도적으로 LIKE '%keyword%' 방식의 비효율적 검색을 사용한다.
 * 이후 최적화 단계에서 역인덱스, 캐싱 등으로 개선한다.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    /** 게시글 목록 (OFFSET 페이지네이션 - 의도적 비효율) */
    Page<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 카테고리별 게시글 목록 */
    Page<Post> findByCategoryIdOrderByCreatedAtDesc(Long categoryId, Pageable pageable);

    /**
     * FULLTEXT ngram 검색 (4단계).
     * LIKE '%keyword%' Full Table Scan -> MATCH(title, content) AGAINST로 전환.
     * ngram parser가 한국어를 2-gram으로 토큰화하여 부분 검색을 지원한다.
     * BOOLEAN MODE: 50% threshold 없이 매칭, 27M rows에서 더 예측 가능.
     */
    @Query(value = """
        SELECT * FROM posts
        WHERE MATCH(title, content) AGAINST(:keyword IN BOOLEAN MODE)
        ORDER BY MATCH(title, content) AGAINST(:keyword IN BOOLEAN MODE) DESC, created_at DESC
        LIMIT :#{#pageable.pageSize}
        OFFSET :#{#pageable.offset}
        """,
        countQuery = """
        SELECT COUNT(*) FROM posts
        WHERE MATCH(title, content) AGAINST(:keyword IN BOOLEAN MODE)
        """,
        nativeQuery = true)
    Page<Post> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 자동완성 v1: 제목 prefix 매칭 (LIKE 'prefix%').
     * 인덱스를 타긴 하지만 2,744만 건에서는 여전히 느림.
     */
    @Query("SELECT p FROM Post p WHERE p.title LIKE :prefix% ORDER BY p.viewCount DESC")
    List<Post> findByTitleStartingWith(@Param("prefix") String prefix, Pageable pageable);

    /** 조회수를 원자적으로 1 증가시킨다. */
    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /** 좋아요 수를 원자적으로 1 증가시킨다. */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    /** 좋아요 수를 원자적으로 1 감소시킨다. (0 이하 방지) */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount - 1 WHERE p.id = :id AND p.likeCount > 0")
    void decrementLikeCount(@Param("id") Long id);
}
