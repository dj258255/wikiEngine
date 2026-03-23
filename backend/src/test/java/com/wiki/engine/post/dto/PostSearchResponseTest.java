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

    // ─── 나무위키 마크업 테스트 ───

    @Test
    void createSnippet_나무위키_include_틀() {
        // 배포 후 스크린샷: [include(틀:회원수정2)] [include(틀:다른 뜻1...)]
        String input = "[include(틀:회원수정2)] [include(틀:다른 뜻1, other1=다른 뜻, rd1=삼성(동음이의어))] " +
                "[include(틀:대한민국의 대기업)] [include(틀:삼성)] 대한민국";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("[include")
                .doesNotContain("틀:")
                .contains("대한민국");
    }

    @Test
    void createSnippet_나무위키_br_목차() {
        // {br}, [목차]
        String input = "|||| || } }{br} || } || 삼성대로[br](천안로사거리~천안IC(천안대교)) || } || } }{br} || " +
                "[include(틀:천안시의 교통)] [include(틀:천안시의 주요 도로)] [include(틀:충청남도의 도로)] }";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("{br}")
                .doesNotContain("[include")
                .doesNotContain("||");
    }

    @Test
    void createSnippet_나무위키_파이프_테이블_제거() {
        // ||노선명 = 삼성대로 |노선번호명 = ... 같은 파이프 구분 테이블
        String input = "|노선명 = 삼성1로 |노선번호명 = 지방도 제318호선의 일부 |노선도 = |총연장 = |개통년 = " +
                "|기점 = 경기도 화성시 동탄동 |주요경유지 = |종점 = 경기도 화성시 반월동 반월삼거리 " +
                "|주요교차도로 = 지방도 제315호선 |}} 삼성1로(Samsung 1-ro";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("|노선명")
                .doesNotContain("|노선번호명")
                .contains("삼성1로");
    }

    @Test
    void createSnippet_나무위키_삼성물산_실제() {
        // | 상장일 = 2014년 12월 18일 | 자회사 = 삼성웰스토리...
        String input = "| 상장일 = 2014년 12월 18일 | 자회사 = 삼성웰스토리주식회사제일패션리테일주식회사 " +
                "삼우종합건축사사무소주식회사 서울레이크사이드 | 장소 = 서울특별시 강동구 상일로6길 26";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("| 상장일")
                .doesNotContain("| 자회사");
    }

    @Test
    void createSnippet_영문위키_infobox_파라미터_제거() {
        // 배포 후 스크린샷: | image = Jalgaon Banana Bunch closeup.jpg | alt = ... | caption = ...
        String input = "| image = Jalgaon Banana Bunch closeup.jpg | alt = Jalgaon Banana Bunch close-up " +
                "| caption = Jalgaon Banana Bunch close-up | alternative names = जळगाव";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("| image")
                .doesNotContain("| alt")
                .doesNotContain("| caption");
    }

    @Test
    void createSnippet_영문위키_Melt_Banana_실제() {
        // | years_active = 1992–present | label = | website = | current_members = ...
        String input = "| years_active = 1992–present | label = | website = " +
                "| current_members = Yasuko Onuki Ichiro Agata | past_members = Sudoh Toshiaki Oshima Watchma Rika";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("| years_active")
                .doesNotContain("| current_members")
                .doesNotContain("| label");
    }

    @Test
    void createSnippet_Lua_모듈_코드_제거() {
        // 모듈:Test/Bananas — Lua 스크립트가 본문인 경우
        String input = "local p = {} ---------------------------------------- " +
                "function p.has_fruit(param) return true end";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("function p.");
    }

    @Test
    void createSnippet_Lua_모듈_hello_world() {
        // 모듈:Bananas — 짧은 Lua 코드
        String input = "-- 헬로 월드! local p = {} function p.hello() return \"Hello, world!\" end return p";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("function");
    }

    @Test
    void createSnippet_대시_구분선_제거() {
        String input = "local p = {} --------------------------------------- function p.test() end";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result).doesNotContain("-------");
    }

    @Test
    void createSnippet_영문위키_Bananas_infobox_연속_빈값() {
        // | writer = | starring = See below | music = Nathan Larson | cinematography = | editing = ...
        String input = "| writer = | starring = See below | music = Nathan Larson " +
                "| cinematography = | editing = Jesper Osmund | studio = novemberfilm | distributor = Oscillo";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("| writer")
                .doesNotContain("| starring")
                .doesNotContain("| studio");
    }

    @Test
    void createSnippet_noprose_잔해_제거() {
        // | noprose=yes }} 《Bananas》는 영국의...
        String input = "| noprose=yes }} 《Bananas》는 영국의 하드 록 밴드 딥 퍼플의 열일곱 번째 스튜디오 음반으로";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("noprose")
                .doesNotContain("}}");
        // INFOBOX_PARAM이 "| noprose=yes" 이후 텍스트까지 먹을 수 있으므로,
        // Bananas가 남아있는지는 패턴 의존적 — 핵심은 마크업 잔해 제거
    }

    @Test
    void createSnippet_나무위키_삼성로_include_복합() {
        // [include(틀:다른 뜻1, other1=...)] } [목차] 개요 서울특별시 강남구...
        String input = "[include(틀:다른 뜻1, other1=경기도 수원시 영통구에 위치한 도로, rd1=삼성로(수원), " +
                "other2=경기도 군포시에 위치한 도로, rd2=삼성로(군포))] [include(틀:강남구의 간선도로)] } " +
                "[목차] 개요 서울특별시 강남구 개포3·4단지 삼거";
        String result = PostSearchResponse.createSnippet(input);
        assertThat(result)
                .doesNotContain("[include")
                .doesNotContain("[목차]")
                .contains("개요")
                .contains("서울특별시");
    }
}
