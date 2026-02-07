package com.wiki.engine.post;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 태그 엔티티.
 * 위키피디아 문서의 [[분류:XXX]] / [[Category:XXX]]에서 추출한 태그를 저장한다.
 * 하나의 게시글에 여러 태그가 붙을 수 있다 (다대다 관계, PostTag로 연결).
 */
@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 태그 이름 (고유값, 최대 255자) */
    @Column(nullable = false, unique = true, length = 255)
    private String name;

    public Tag(String name) {
        this.name = name;
    }
}
