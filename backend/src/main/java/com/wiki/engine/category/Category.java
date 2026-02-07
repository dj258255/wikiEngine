package com.wiki.engine.category;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카테고리 엔티티.
 * 게시글(Post)을 분류하기 위한 계층형 카테고리 구조를 표현한다.
 * parentId를 통해 상위-하위 카테고리 관계를 형성한다.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 카테고리 이름 (고유값) */
    @Column(nullable = false, unique = true)
    private String name;

    /** 상위 카테고리 ID (null이면 최상위 카테고리) */
    @Column(name = "parent_id")
    private Long parentId;

    @Builder
    public Category(String name, Long parentId) {
        this.name = name;
        this.parentId = parentId;
    }
}
