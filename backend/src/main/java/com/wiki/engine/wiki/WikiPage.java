package com.wiki.engine.wiki;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 위키피디아 페이지 엔티티.
 * 위키피디아 덤프 XML에서 파싱한 데이터를 임시로 저장하는 테이블이다.
 * ID는 위키피디아 원본의 page ID를 그대로 사용한다 (자동 생성 아님).
 *
 * 인덱스는 성능 최적화 단계에서 병목 확인 후 추가한다.
 */
@Entity
@Table(name = "wiki_pages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WikiPage {

    /** 위키피디아 원본 page ID (자동 생성 아님) */
    @Id
    private Long id;

    /** 문서 제목 (최대 512자) */
    @Column(nullable = false, length = 512)
    private String title;

    /** 네임스페이스 번호 (0: 일반 문서, 1: 토론, 2: 사용자 등) */
    @Column(nullable = false)
    private Integer namespace;

    /** 문서 본문 (위키텍스트 원문, LONGTEXT) */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 리다이렉트 대상 문서 제목 (null이면 리다이렉트가 아닌 일반 문서) */
    @Column(name = "redirect_to", length = 512)
    private String redirectTo;

    /** 문서 최종 수정 시각 (위키피디아 revision timestamp) */
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
