package com.wiki.engine.post.internal.search;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "search_logs", indexes = {
        @Index(name = "idx_search_logs_time_bucket", columnList = "time_bucket")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_search_logs_query_bucket", columnNames = {"query", "time_bucket"})
})
class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String query;

    @Column(nullable = false)
    private LocalDateTime timeBucket;

    @Column(nullable = false)
    private long count;

    protected SearchLog() {
    }

    String getQuery() {
        return query;
    }

    LocalDateTime getTimeBucket() {
        return timeBucket;
    }

    long getCount() {
        return count;
    }
}
