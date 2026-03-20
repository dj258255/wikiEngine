package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import org.springframework.data.domain.Page;
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
 *
 * 1단계에서는 의도적으로 LIKE '%keyword%' 방식의 비효율적 검색을 사용한다.
 * 이후 최적화 단계에서 역인덱스, 캐싱 등으로 개선한다.
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
     * FULLTEXT ngram 검색 (4단계).
     * 본 테이블(posts)에 ngram 인덱스를 생성하면 content(LONGTEXT) 때문에
     * 인덱스가 수백 GB에 달해 디스크를 초과하므로,
     * 한국어 데이터(category_id=1)만 복사한 tmp_namu_posts에서 검색한다.
     * 본문 검색은 이후 Lucene + Nori로 전환 예정.
     */
    @Query(value = """
        SELECT * FROM tmp_namu_posts
        WHERE MATCH(title, content) AGAINST(:keyword IN BOOLEAN MODE)
        ORDER BY MATCH(title, content) AGAINST(:keyword IN BOOLEAN MODE) DESC, created_at DESC
        LIMIT :#{#pageable.pageSize}
        OFFSET :#{#pageable.offset}
        """,
        countQuery = """
        SELECT COUNT(*) FROM tmp_namu_posts
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

    /**
     * Lucene 배치 인덱싱용 cursor 기반 조회.
     * ID 순으로 batchSize만큼 읽어온다 (OFFSET 없이 WHERE id > lastId).
     */
    @Query(value = "SELECT * FROM posts WHERE id > :lastId ORDER BY id ASC LIMIT :batchSize", nativeQuery = true)
    List<Post> findBatchAfterId(@Param("lastId") long lastId, @Param("batchSize") int batchSize);

    /**
     * Trie 초기화용 제목 조회. title만 필요하므로 projection.
     * PK 인덱스(ORDER BY id DESC)를 사용하여 filesort 없이 빠르게 반환.
     *
     * 위키 덤프 데이터는 viewCount/likeCount가 대부분 0이므로
     * ORDER BY (view_count + like_count)는 인덱스가 없어 Full Table Scan 발생.
     * 검색 로그가 충분히 쌓이면 인기도 기반으로 전환 가능.
     */
    @Query(value = "SELECT title FROM posts ORDER BY id DESC LIMIT :limit", nativeQuery = true)
    List<String> findTopTitles(@Param("limit") int limit);

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
