package com.wiki.engine.post.dto;

import com.wiki.engine.post.Post;

import java.time.Instant;

public record PostDetailResponse(
        Long id,
        String title,
        String content,
        Long authorId,
        String authorNickname,
        Long categoryId,
        Long viewCount,
        Long likeCount,
        boolean liked,
        Instant createdAt,
        Instant updatedAt
) {
    public static PostDetailResponse from(Post post, String authorNickname, boolean liked) {
        return new PostDetailResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getAuthorId(),
                authorNickname,
                post.getCategoryId(),
                post.getViewCount(),
                post.getLikeCount(),
                liked,
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
