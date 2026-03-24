package com.wiki.engine.post.dto;

import com.wiki.engine.post.Post;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 검색 결과 전용 DTO.
 * content(LONGTEXT) 대신 snippet(150자)만 반환하여 응답 크기를 ~99% 절감.
 * 위키 마크업({{틀}}, [[링크]], {| 테이블 |} 등)을 제거하여 순수 텍스트만 표시.
 */
public record PostSearchResponse(
        Long id,
        String title,
        String snippet,
        Long viewCount,
        Long likeCount,
        Instant createdAt
) {
    private static final int SNIPPET_LENGTH = 150;

    // 위키 마크업 제거 패턴 — 컴파일 비용을 피하기 위해 static final
    // 순서가 중요: 중첩 구조({{ }})를 먼저 제거해야 내부 | 가 남지 않음
    // DOTALL: . 이 줄바꿈도 매칭하도록 (틀이 여러 줄에 걸칠 수 있음)
    // 닫히지 않은 틀(snippet에서 잘린 경우)도 제거: {{ 이후 끝까지
    private static final Pattern WIKI_TEMPLATE = Pattern.compile("\\{\\{[\\s\\S]*?(\\}\\}|$)");
    private static final Pattern WIKI_TABLE = Pattern.compile("\\{\\|[\\s\\S]*?(\\|\\}|$)");
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[(?:[^|\\]]*\\|)?([^\\]]*?)\\]\\]");
    private static final Pattern WIKI_EXT_LINK = Pattern.compile("\\[https?://[^\\s\\]]+(\\s[^\\]]*)?\\]");
    private static final Pattern WIKI_REF = Pattern.compile("<ref[^>]*>.*?</ref>|<ref[^/]*/>");
    private static final Pattern WIKI_HEADING = Pattern.compile("^=+\\s*(.+?)\\s*=+$", Pattern.MULTILINE);
    private static final Pattern WIKI_BOLD_ITALIC = Pattern.compile("'{2,5}");
    private static final Pattern WIKI_CATEGORY = Pattern.compile("\\[\\[(분류|Category):[^\\]]*\\]\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKI_FILE = Pattern.compile("\\[\\[(파일|File|Image):[^\\]]*\\]\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern HTML_ENTITIES = Pattern.compile("&[a-zA-Z]+;|&#\\d+;");
    private static final Pattern MULTI_SPACES = Pattern.compile("[\\s]+");

    // 나무위키 고유 마크업
    // 나무위키 include: 중첩 괄호 허용 — [include(틀:다른 뜻1, other1=값(서브))]
    private static final Pattern NAMU_INCLUDE = Pattern.compile("\\[include\\([^\\]]*\\)\\]");
    private static final Pattern NAMU_BR = Pattern.compile("\\{br\\}");
    private static final Pattern NAMU_TOC = Pattern.compile("\\[목차\\]|\\[tableofcontents\\]");
    private static final Pattern NAMU_FOOTNOTE = Pattern.compile("\\[\\*[^\\]]*\\]");
    private static final Pattern NAMU_IMAGE = Pattern.compile("\\[\\[파일:[^\\]]*\\]\\]|\\[파일:[^\\]]*\\]");
    private static final Pattern NAMU_PIPE_TABLE = Pattern.compile("\\|\\|[^\\n]*\\|\\|");
    private static final Pattern NAMU_MACRO = Pattern.compile("\\{\\{\\{[^}]*\\}\\}\\}");
    private static final Pattern NAMU_COLOR = Pattern.compile("\\{\\{\\{#[0-9a-fA-F]{3,6}\\s");
    // Infobox 파라미터 라인: "| key = value" 패턴 (영문/한국어 공통)
    // \w는 Java에서 한국어를 포함하지 않으므로 Unicode letter/digit 사용
    private static final Pattern INFOBOX_PARAM = Pattern.compile("\\|\\s*[\\p{L}\\p{N}\\s_-]+=\\s*[^|}\\n]*");
    // Lua 모듈 코드 (위키 모듈 문서)
    private static final Pattern LUA_CODE = Pattern.compile("local\\s+\\w+\\s*=\\s*\\{\\}[\\s\\S]*?(?:end|return\\s+\\w+)");
    private static final Pattern LUA_FUNCTION = Pattern.compile("function\\s+[\\w.:]+\\([^)]*\\)[\\s\\S]*?end");
    private static final Pattern LUA_REQUIRE = Pattern.compile("require\\(['\"][^'\"]*['\"]\\)");
    // 대시 구분선 (---...---)
    private static final Pattern DASH_LINE = Pattern.compile("-{3,}");
    // 남은 잔해
    private static final Pattern REMAINING_BRACES = Pattern.compile("[{}\\[\\]]+");

    public static PostSearchResponse from(Post post) {
        return new PostSearchResponse(
                post.getId(),
                post.getTitle(),
                createSnippet(post.getContent()),
                post.getViewCount(),
                post.getLikeCount(),
                post.getCreatedAt()
        );
    }

    /**
     * Phase 18: UnifiedHighlighter가 생성한 snippet을 직접 사용.
     * Highlighter snippet에는 <b> 태그가 포함될 수 있으며, 위키 마크업도 이미 정리된 상태.
     */
    public static PostSearchResponse fromWithSnippet(Post post, String highlightedSnippet) {
        // Highlighter snippet에서도 위키 마크업 잔해가 있을 수 있으므로 정리
        String cleaned = createSnippet(highlightedSnippet);
        return new PostSearchResponse(
                post.getId(),
                post.getTitle(),
                cleaned,
                post.getViewCount(),
                post.getLikeCount(),
                post.getCreatedAt()
        );
    }

    static String createSnippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String plain = content;

        // 1. 나무위키 고유 마크업 제거 (위키피디아 패턴보다 먼저)
        plain = NAMU_INCLUDE.matcher(plain).replaceAll("");
        plain = NAMU_MACRO.matcher(plain).replaceAll("");
        plain = NAMU_COLOR.matcher(plain).replaceAll("");
        plain = NAMU_BR.matcher(plain).replaceAll(" ");
        plain = NAMU_TOC.matcher(plain).replaceAll("");
        plain = NAMU_FOOTNOTE.matcher(plain).replaceAll("");
        plain = NAMU_IMAGE.matcher(plain).replaceAll("");
        plain = NAMU_PIPE_TABLE.matcher(plain).replaceAll("");

        // 2. 위키피디아 틀/테이블 제거 (내부에 | 포함)
        plain = WIKI_TEMPLATE.matcher(plain).replaceAll("");
        plain = WIKI_TABLE.matcher(plain).replaceAll("");

        // 3. 카테고리/파일 링크 제거 (텍스트 추출 불필요)
        plain = WIKI_CATEGORY.matcher(plain).replaceAll("");
        plain = WIKI_FILE.matcher(plain).replaceAll("");

        // 4. 위키 링크 → 표시 텍스트 추출: [[대한민국|한국]] → 한국, [[서울]] → 서울
        plain = WIKI_LINK.matcher(plain).replaceAll("$1");

        // 5. 외부 링크, ref 태그, HTML 태그 제거
        plain = WIKI_EXT_LINK.matcher(plain).replaceAll("");
        plain = WIKI_REF.matcher(plain).replaceAll("");
        plain = HTML_TAGS.matcher(plain).replaceAll("");
        plain = HTML_ENTITIES.matcher(plain).replaceAll(" ");

        // 6. 위키 서식 제거 (볼드/이탤릭, 헤딩)
        plain = WIKI_HEADING.matcher(plain).replaceAll("$1");
        plain = WIKI_BOLD_ITALIC.matcher(plain).replaceAll("");

        // 7. Lua 코드 제거 (위키 모듈 문서)
        plain = LUA_CODE.matcher(plain).replaceAll("");
        plain = LUA_FUNCTION.matcher(plain).replaceAll("");
        plain = LUA_REQUIRE.matcher(plain).replaceAll("");

        // 8. 남은 마크업 잔해 정리: | key = value (Infobox 파라미터), 대시 구분선, 고립된 {}, [] 등
        plain = INFOBOX_PARAM.matcher(plain).replaceAll("");
        plain = DASH_LINE.matcher(plain).replaceAll(" ");
        plain = REMAINING_BRACES.matcher(plain).replaceAll(" ");

        // 9. 정리: 다중 공백 → 단일 공백
        plain = MULTI_SPACES.matcher(plain).replaceAll(" ").strip();

        if (plain.length() <= SNIPPET_LENGTH) {
            return plain;
        }
        return plain.substring(0, SNIPPET_LENGTH);
    }
}
