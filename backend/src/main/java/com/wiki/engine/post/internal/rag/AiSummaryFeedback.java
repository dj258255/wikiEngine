package com.wiki.engine.post.internal.rag;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ai_summary_feedback")
public class AiSummaryFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String query;

    private Long userId;

    @Column(nullable = false)
    private int rating;  // 1=thumbs_up, -1=thumbs_down

    @Column(length = 32)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(length = 64)
    private String modelName = "gemini-2.0-flash";

    @Column(length = 32)
    private String promptVersion = "v1";

    private Integer inputTokens;
    private Integer outputTokens;
    private Integer latencyMs;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected AiSummaryFeedback() {}

    public AiSummaryFeedback(String query, Long userId, int rating, String category, String comment) {
        this.query = query;
        this.userId = userId;
        this.rating = rating;
        this.category = category;
        this.comment = comment;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getQuery() { return query; }
    public int getRating() { return rating; }
    public String getCategory() { return category; }
}
