package com.wiki.engine.post.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneSearchServiceTest {

    @Test
    void escapePreservingPhrases_일반_키워드는_escape() {
        String result = LuceneSearchService.escapePreservingPhrases("삼성전자 (주)");
        assertThat(result).isEqualTo("삼성전자 \\(주\\)");
    }

    @Test
    void escapePreservingPhrases_큰따옴표_구절_보존() {
        String result = LuceneSearchService.escapePreservingPhrases("\"삼성전자 반도체\"");
        assertThat(result).isEqualTo("\"삼성전자 반도체\"");
    }

    @Test
    void escapePreservingPhrases_혼합_입력() {
        String result = LuceneSearchService.escapePreservingPhrases("투자 \"반도체 기술\" (주)");
        assertThat(result).isEqualTo("투자 \"반도체 기술\" \\(주\\)");
    }

    @Test
    void escapePreservingPhrases_닫는_따옴표_없으면_전체_escape() {
        String result = LuceneSearchService.escapePreservingPhrases("\"반도체 기술");
        // 닫는 따옴표 없으면 "도 escape됨
        assertThat(result).isEqualTo("\\\"반도체 기술");
    }

    @Test
    void escapePreservingPhrases_빈_문자열() {
        assertThat(LuceneSearchService.escapePreservingPhrases("")).isEmpty();
        assertThat(LuceneSearchService.escapePreservingPhrases(null)).isNull();
    }

    @Test
    void escapePreservingPhrases_일반_키워드_특수문자_없음() {
        String result = LuceneSearchService.escapePreservingPhrases("인공지능 기술");
        assertThat(result).isEqualTo("인공지능 기술");
    }
}
