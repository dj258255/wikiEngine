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

    // ── 위키 마크업 제거 패턴 ──
    // 순서가 중요: 중첩 구조({{ }})를 먼저 제거해야 내부 | 가 남지 않음

    // ref 태그 — 내부에 {{웹 인용}} 등 중첩 틀이 있어도 잡아야 함 (DOTALL로 줄바꿈 포함)
    private static final Pattern WIKI_REF = Pattern.compile("<ref[^>]*>[\\s\\S]*?</ref>|<ref[^/]*/>", Pattern.DOTALL);

    // 나무위키 매크로: {{{+4 "'Basa Jawa'"}}} 같은 변형 포함
    private static final Pattern NAMU_MACRO = Pattern.compile("\\{\\{\\{[^}]*\\}\\}\\}|\\{\\{\\{[\\s\\S]*?\\}\\}\\}");
    private static final Pattern NAMU_COLOR = Pattern.compile("\\{\\{\\{#[0-9a-fA-F]{3,6}[\\s\\S]*?\\}\\}\\}");

    // 위키 틀/테이블 — 여러 줄에 걸치는 것도 잡음 (DOTALL)
    private static final Pattern WIKI_TEMPLATE = Pattern.compile("\\{\\{[\\s\\S]*?(\\}\\}|$)");
    private static final Pattern WIKI_TABLE = Pattern.compile("\\{\\|[\\s\\S]*?(\\|\\}|$)");

    // 위키 링크 — [[파일:xxx|섬네일|설명]], [[분류:xxx]] 등 파이프 여러 개 포함
    private static final Pattern WIKI_FILE = Pattern.compile("\\[\\[(파일|File|Image):[^\\]]*\\]\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKI_CATEGORY = Pattern.compile("\\[\\[(분류|Category):[^\\]]*\\]\\]", Pattern.CASE_INSENSITIVE);
    // 일반 위키 링크 — [[대한민국|한국]] → 한국, [[서울]] → 서울
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[(?:[^|\\]]*\\|)?([^\\]]*?)\\]\\]");
    private static final Pattern WIKI_EXT_LINK = Pattern.compile("\\[https?://[^\\s\\]]+(\\s[^\\]]*)?\\]");

    // HTML
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    private static final Pattern HTML_ENTITIES = Pattern.compile("&[a-zA-Z]+;|&#\\d+;");

    // 위키 서식
    private static final Pattern WIKI_HEADING = Pattern.compile("^=+\\s*(.+?)\\s*=+$", Pattern.MULTILINE);
    private static final Pattern WIKI_BOLD_ITALIC = Pattern.compile("'{2,5}");

    // 나무위키 고유
    // include: HTML 속성 포함 변형 — [include(틀:다른 뜻1, other1=<a href="...">...</a>, rd1=Java)]
    private static final Pattern NAMU_INCLUDE = Pattern.compile("\\[include\\([^\\]]*\\)]");
    private static final Pattern NAMU_BR = Pattern.compile("\\{br\\}");
    private static final Pattern NAMU_TOC = Pattern.compile("\\[목차]|\\[tableofcontents]");
    private static final Pattern NAMU_FOOTNOTE = Pattern.compile("\\[\\*[^\\]]*]");
    private static final Pattern NAMU_IMAGE = Pattern.compile("\\[\\[파일:[^\\]]*]]|\\[파일:[^\\]]*]");
    private static final Pattern NAMU_PIPE_TABLE = Pattern.compile("\\|\\|[^\\n]*\\|\\|");

    // Infobox/틀 파라미터 — "| key = value" (파이프 시작, = 포함)
    private static final Pattern INFOBOX_PARAM = Pattern.compile("\\|\\s*[\\p{L}\\p{N}\\s_-]+=\\s*[^|}\\n]*");
    // 단독 파이프 + key=value 패턴 (파이프 뒤 한글/영문 키)
    private static final Pattern PIPE_PARAM = Pattern.compile("\\|[\\p{L}\\p{N}_-]+=");

    // Lua 모듈 코드
    private static final Pattern LUA_CODE = Pattern.compile("local\\s+\\w+\\s*=\\s*\\{\\}[\\s\\S]*?(?:end|return\\s+\\w+)");
    private static final Pattern LUA_FUNCTION = Pattern.compile("function\\s+[\\w.:]+\\([^)]*\\)[\\s\\S]*?end");
    private static final Pattern LUA_REQUIRE = Pattern.compile("require\\(['\"][^'\"]*['\"]\\)");

    // 정리용
    private static final Pattern DASH_LINE = Pattern.compile("-{3,}");
    private static final Pattern REMAINING_BRACES = Pattern.compile("[{}\\[\\]]+");
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

    /**
     * Phase 18: UnifiedHighlighter가 생성한 snippet을 직접 사용.
     * snippetSource에 이미 clean text가 저장되어 있으므로 이중 정리 불필요.
     * 하이라이터의 &lt;b&gt; 태그는 보존하여 프론트엔드에서 강조 표시 가능.
     */
    public static PostSearchResponse fromWithSnippet(Post post, String highlightedSnippet) {
        String snippet = highlightedSnippet;
        if (snippet.length() > SNIPPET_LENGTH) {
            snippet = snippet.substring(0, SNIPPET_LENGTH);
        }
        return new PostSearchResponse(
                post.getId(),
                post.getTitle(),
                snippet,
                post.getViewCount(),
                post.getLikeCount(),
                post.getCreatedAt()
        );
    }

    /**
     * 위키 마크업을 제거하여 plain text를 반환한다.
     * 길이 제한 없이 정리만 수행 — 인덱싱 시 snippetSource 저장에 사용.
     */
    public static String stripMarkup(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String plain = content;

        // === 1단계: ref 태그 먼저 제거 (내부에 {{웹 인용}} 등 중첩 틀 포함) ===
        plain = WIKI_REF.matcher(plain).replaceAll("");

        // === 2단계: 나무위키 고유 마크업 ===
        plain = NAMU_INCLUDE.matcher(plain).replaceAll("");
        plain = NAMU_COLOR.matcher(plain).replaceAll("");
        plain = NAMU_MACRO.matcher(plain).replaceAll("");
        plain = NAMU_BR.matcher(plain).replaceAll(" ");
        plain = NAMU_TOC.matcher(plain).replaceAll("");
        plain = NAMU_FOOTNOTE.matcher(plain).replaceAll("");
        plain = NAMU_IMAGE.matcher(plain).replaceAll("");
        plain = NAMU_PIPE_TABLE.matcher(plain).replaceAll("");

        // === 3단계: 위키 틀/테이블 (여러 줄 걸치는 것 포함) ===
        plain = WIKI_TEMPLATE.matcher(plain).replaceAll("");
        plain = WIKI_TABLE.matcher(plain).replaceAll("");

        // === 4단계: 카테고리/파일 링크 (텍스트 추출 불필요) ===
        plain = WIKI_CATEGORY.matcher(plain).replaceAll("");
        plain = WIKI_FILE.matcher(plain).replaceAll("");

        // === 5단계: 위키 링크 → 표시 텍스트 추출 ===
        plain = WIKI_LINK.matcher(plain).replaceAll("$1");

        // === 6단계: HTML 태그/엔터티 + 외부 링크 ===
        plain = WIKI_EXT_LINK.matcher(plain).replaceAll("");
        plain = HTML_TAGS.matcher(plain).replaceAll("");
        plain = HTML_ENTITIES.matcher(plain).replaceAll(" ");

        // === 7단계: 위키 서식 (볼드/이탤릭, 헤딩) ===
        plain = WIKI_HEADING.matcher(plain).replaceAll("$1");
        plain = WIKI_BOLD_ITALIC.matcher(plain).replaceAll("");

        // === 8단계: Lua 코드 ===
        plain = LUA_CODE.matcher(plain).replaceAll("");
        plain = LUA_FUNCTION.matcher(plain).replaceAll("");
        plain = LUA_REQUIRE.matcher(plain).replaceAll("");

        // === 9단계: 남은 잔해 정리 ===
        plain = INFOBOX_PARAM.matcher(plain).replaceAll("");
        plain = PIPE_PARAM.matcher(plain).replaceAll(" ");  // |이름= |로고= 등 단독 파라미터
        plain = DASH_LINE.matcher(plain).replaceAll(" ");
        plain = REMAINING_BRACES.matcher(plain).replaceAll(" ");

        // === 10단계: 다중 공백 → 단일 공백 ===
        return MULTI_SPACES.matcher(plain).replaceAll(" ").strip();
    }

    /**
     * 위키 마크업 제거 + 150자 잘라서 snippet 반환.
     * 검색 결과 응답용.
     */
    public static String createSnippet(String content) {
        // 전체 content에 stripMarkup 적용 시 수만 자에서 정규식 backtracking 성능 문제
        // 앞 1500자만 잘라서 처리 — 150자 snippet에 충분하고, 정규식도 안정적
        String truncated = (content != null && content.length() > 1500)
                ? content.substring(0, 1500) : content;
        String plain = stripMarkup(truncated);
        if (plain.length() <= SNIPPET_LENGTH) {
            return plain;
        }
        return plain.substring(0, SNIPPET_LENGTH);
    }
}
