package com.wiki.engine.post.internal.search;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

interface ClickLogRepository extends JpaRepository<ClickLog, Long> {

    /**
     * 가장 최근 클릭 로그 조회 (dwell time 업데이트용).
     * session + post 조합으로 매칭하여 dwell time을 나중에 채운다.
     */
    Optional<ClickLog> findTopBySessionIdAndPostIdOrderByCreatedAtDesc(
            String sessionId, Long postId);

    /**
     * 오래된 클릭 로그 정리 (30일 보관).
     */
    @Modifying
    @Query("DELETE FROM ClickLog c WHERE c.createdAt < :cutoff")
    int deleteOlderThan(LocalDateTime cutoff);
}
