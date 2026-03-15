package com.wiki.engine.post.dto;

import com.wiki.engine.post.Post;

import java.time.Instant;

/**
 * 검색 결과 전용 DTO.
 * content(LONGTEXT) 대신 snippet(150자)만 반환하여 응답 크기를 ~99% 절감.
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

    private static String createSnippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String plain = content.replaceAll("<[^>]*>", "").strip();
        if (plain.length() <= SNIPPET_LENGTH) {
            return plain;
        }
        return plain.substring(0, SNIPPET_LENGTH);
    }
}
