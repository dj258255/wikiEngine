package com.wiki.engine.post.internal;

import com.wiki.engine.post.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 게시글 좋아요 JPA 레포지토리.
 * internal 패키지에 위치하여 모듈 외부에서 직접 접근할 수 없다.
 * 같은 모듈의 PostService를 통해서만 접근 가능하다.
 */
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    /**
     * 좋아요를 추가한다 (INSERT IGNORE).
     * UNIQUE 제약(post_id, user_id)에 걸리면 무시하여 중복 좋아요를 방지한다.
     * 동시에 여러 요청이 들어와도 DB 레벨에서 안전하게 처리된다.
     *
     * @return 삽입된 행 수 (1: 새로 추가, 0: 이미 존재)
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO post_likes (post_id, user_id, created_at) VALUES (:postId, :userId, NOW())", nativeQuery = true)
    int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);

    /**
     * 특정 사용자의 특정 게시글 좋아요를 삭제한다 (좋아요 취소).
     *
     * @return 삭제된 행 수 (1: 취소 성공, 0: 좋아요 기록 없음)
     */
    @Modifying
    @Query("DELETE FROM PostLike pl WHERE pl.postId = :postId AND pl.userId = :userId")
    int deleteByPostIdAndUserId(@Param("postId") Long postId, @Param("userId") Long userId);

    /** 특정 게시글의 모든 좋아요를 삭제한다 (게시글 삭제 시 사용). */
    @Modifying
    @Query("DELETE FROM PostLike pl WHERE pl.postId = :postId")
    void deleteByPostId(@Param("postId") Long postId);

    /** 특정 사용자가 특정 게시글에 좋아요를 눌렀는지 확인한다. */
    boolean existsByPostIdAndUserId(Long postId, Long userId);
}
