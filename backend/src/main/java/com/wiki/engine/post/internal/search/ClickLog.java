package com.wiki.engine.post.internal.search;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 검색 결과 클릭 로그 — LTR implicit feedback 수집.
 *
 * <p>position, dwell_time이 핵심 컬럼.
 */
@Entity
@Table(name = "click_logs")
class ClickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String query;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Short clickPosition;

    private Long dwellTimeMs;

    @Column(length = 36)
    private String sessionId;

    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected ClickLog() {}

    ClickLog(String query, Long postId, Short clickPosition,
             String sessionId, Long userId) {
        this.query = query;
        this.postId = postId;
        this.clickPosition = clickPosition;
        this.sessionId = sessionId;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
    }

    void updateDwellTime(Long dwellTimeMs) {
        this.dwellTimeMs = dwellTimeMs;
    }

    Long getId() { return id; }
    String getQuery() { return query; }
    Long getPostId() { return postId; }
    Short getClickPosition() { return clickPosition; }
    Long getDwellTimeMs() { return dwellTimeMs; }
    String getSessionId() { return sessionId; }
    Long getUserId() { return userId; }
    LocalDateTime getCreatedAt() { return createdAt; }
}
