package com.wiki.engine.wiki;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 위키 덤프 파싱 결과를 담는 DTO.
 * 파서(WikiXmlParser, NamuWikiJsonParser)에서 생성하여 WikiImportService로 전달한다.
 * DB 테이블과 매핑되지 않는 순수 데이터 객체이다.
 */
@Getter
@Builder
public class WikiPage {

    private final Long id;
    private final String title;
    private final Integer namespace;
    private final String content;
    private final String redirectTo;
    private final Instant createdAt;
}
