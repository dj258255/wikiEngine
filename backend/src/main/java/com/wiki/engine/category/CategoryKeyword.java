package com.wiki.engine.category;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카테고리 자동 분류용 키워드 매핑.
 * 게시글 제목에 키워드가 포함되면 해당 카테고리로 분류한다.
 * CategoryClassificationService에서 사용.
 */
@Entity
@Table(name = "category_keywords", indexes = {
        @Index(name = "idx_keyword", columnList = "keyword"),
        @Index(name = "idx_category_name", columnList = "category_name")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_name", nullable = false)
    private String categoryName;

    @Column(nullable = false, length = 100)
    private String keyword;

    @Column(nullable = false)
    private Double weight = 1.0;
}
