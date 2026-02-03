package com.wiki.engine.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "wiki_pages", indexes = {
    @Index(name = "idx_wiki_pages_title", columnList = "title"),
    @Index(name = "idx_wiki_pages_namespace", columnList = "namespace")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WikiPage {

    @Id
    private Long id;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false)
    private Integer namespace;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "redirect_to", length = 512)
    private String redirectTo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Builder
    public WikiPage(Long id, String title, Integer namespace, String content, String redirectTo, LocalDateTime createdAt) {
        this.id = id;
        this.title = title;
        this.namespace = namespace;
        this.content = content;
        this.redirectTo = redirectTo;
        this.createdAt = createdAt;
    }
}
