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

    static String createSnippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String plain = content;

        // 1. 위키 틀/테이블 제거 (가장 먼저 — 내부에 | 포함)
        plain = WIKI_TEMPLATE.matcher(plain).replaceAll("");
        plain = WIKI_TABLE.matcher(plain).replaceAll("");

        // 2. 카테고리/파일 링크 제거 (텍스트 추출 불필요)
        plain = WIKI_CATEGORY.matcher(plain).replaceAll("");
        plain = WIKI_FILE.matcher(plain).replaceAll("");

        // 3. 위키 링크 → 표시 텍스트 추출: [[대한민국|한국]] → 한국, [[서울]] → 서울
        plain = WIKI_LINK.matcher(plain).replaceAll("$1");

        // 4. 외부 링크, ref 태그, HTML 태그 제거
        plain = WIKI_EXT_LINK.matcher(plain).replaceAll("");
        plain = WIKI_REF.matcher(plain).replaceAll("");
        plain = HTML_TAGS.matcher(plain).replaceAll("");
        plain = HTML_ENTITIES.matcher(plain).replaceAll(" ");

        // 5. 위키 서식 제거 (볼드/이탤릭, 헤딩)
        plain = WIKI_HEADING.matcher(plain).replaceAll("$1");
        plain = WIKI_BOLD_ITALIC.matcher(plain).replaceAll("");

        // 6. 정리: 다중 공백 → 단일 공백
        plain = MULTI_SPACES.matcher(plain).replaceAll(" ").strip();

        if (plain.length() <= SNIPPET_LENGTH) {
            return plain;
        }
        return plain.substring(0, SNIPPET_LENGTH);
    }
}
