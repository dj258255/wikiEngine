package com.wiki.engine.post.internal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "synonyms")
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
