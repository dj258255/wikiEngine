package com.wiki.engine.post.internal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "synonyms", indexes = {
        @Index(name = "idx_synonyms_term", columnList = "term")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_term_synonym", columnNames = {"term", "synonym"})
})
@Getter
@NoArgsConstructor
class Synonym {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String term;

    @Column(nullable = false, length = 100)
    private String synonym;

    @Column(nullable = false)
    private Double weight;
}
