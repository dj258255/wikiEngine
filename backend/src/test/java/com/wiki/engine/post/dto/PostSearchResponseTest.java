package com.wiki.engine.post.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class PostSearchResponseTest {

    @ParameterizedTest
    @NullAndEmptySource
    void createSnippet_빈_입력이면_빈_문자열(String input) {
        assertThat(PostSearchResponse.createSnippet(input)).isEmpty();
    }

    @Test
    void createSnippet_위키_틀_제거() {
        // {{좌표|37.496609|127.026902|display=title}} {{기업 정보 | 이름 = 삼성 | 로고 = ...}}
        String input = "{{좌표|37.496609|127.026902|display=title}} {{기업 정보 | 이름 = 삼성}} 삼성그룹은 대한민국의 대기업이다.";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("{{")
                .doesNotContain("}}")
                .doesNotContain("좌표")
                .contains("삼성그룹은 대한민국의 대기업이다");
    }

    @Test
    void createSnippet_위키_링크에서_표시_텍스트_추출() {
        // [[대한민국]] → 대한민국, [[대한민국|한국]] → 한국
        String input = "'''삼성여객'''은 [[대한민국]]의 [[버스|버스 회사]]이다.";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("[[")
                .doesNotContain("]]")
                .contains("대한민국")
                .contains("버스 회사");
    }

    @Test
    void createSnippet_카테고리_분류_제거() {
        // 한국어: [[분류:프로그래밍 언어]], 영문: [[Category:Programming languages]]
        String input = "자바는 프로그래밍 언어이다. [[분류:프로그래밍 언어]] [[Category:Programming languages]]";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("분류:")
                .doesNotContain("Category:")
                .contains("자바는 프로그래밍 언어이다");
    }

    @Test
    void createSnippet_파일_이미지_링크_제거() {
        // [[파일:Samsung.png|align=center]], [[File:Logo.svg|thumb]]
        String input = "삼성전자 [[파일:SamsungTouchWiz.png|align=center]] [목차] 개요";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("파일:")
                .doesNotContain("SamsungTouchWiz")
                .contains("삼성전자")
                .contains("개요");
    }

    @Test
    void createSnippet_위키_테이블_제거() {
        String input = "도로 정보 {| class=\"wikitable\"\n|-\n| 노선명 || 삼성대로\n|} 본문 시작";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("{|")
                .doesNotContain("|}")
                .doesNotContain("wikitable")
                .contains("본문 시작");
    }

    @Test
    void createSnippet_HTML_태그_및_엔티티_제거() {
        String input = "<ref name=\"test\">출처</ref> 총연장=5.2&nbsp;km 서울특별시";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("<ref")
                .doesNotContain("</ref>")
                .doesNotContain("&nbsp;")
                .contains("서울특별시");
    }

    @Test
    void createSnippet_볼드_이탤릭_제거() {
        String input = "'''삼성전자'''는 ''대한민국''의 전자 기업이다.";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("'''")
                .doesNotContain("''")
                .contains("삼성전자")
                .contains("대한민국");
    }

    @Test
    void createSnippet_영문_위키_마크업_제거() {
        // English Wikipedia markup patterns
        String input = "{{Infobox company | name = Samsung | industry = Electronics}} " +
                "'''Samsung Group''' is a [[South Korea]]n [[multinational conglomerate|conglomerate]]. " +
                "[[Category:South Korean companies]] [[File:Samsung logo.svg|thumb]]";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("{{")
                .doesNotContain("[[")
                .doesNotContain("Category:")
                .doesNotContain("File:")
                .contains("Samsung Group")
                .contains("South Korean")
                .contains("conglomerate");
    }

    @Test
    void createSnippet_150자_초과시_잘림() {
        String input = "가".repeat(200);
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result).hasSize(150);
    }

    @Test
    void createSnippet_스크린샷_실제_케이스_삼성그룹() {
        // 스크린샷에서 보이던 실제 데이터
        String input = "{{좌표|37.496609|127.026902|display=title}} {{다른 뜻 넘어옴|삼성}} " +
                "{{기업 정보 | 이름 = 삼성 | 로고 = Samsung old logo before year 2015.svg | 그림 = Samsung headquarters.j";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("{{")
                .doesNotContain("}}")
                .doesNotContain("좌표")
                .doesNotContain("기업 정보");
    }

    @Test
    void createSnippet_스크린샷_실제_케이스_삼성카드() {
        String input = "{{회사 정보 | 이름 = 삼성카드 주식회사 | 영어 = Samsung Card Co., Ltd. | 형태 = 주식회사 | " +
                "창립 = [[1983년]] [[3월 24일]] | 이전 회사 = 천우사한국신용카드 ([[1978년]]~[[1983년]]) " +
                "세종신용카드 ([[198";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("{{")
                .doesNotContain("[[")
                .doesNotContain("회사 정보");
    }
}
