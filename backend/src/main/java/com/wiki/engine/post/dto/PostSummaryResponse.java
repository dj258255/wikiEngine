package com.wiki.engine.post.dto;

import com.wiki.engine.post.Post;

import java.time.Instant;

public record PostSummaryResponse(
        Long id,
        String title,
        Long authorId,
        Long categoryId,
        Long viewCount,
        Long likeCount,
        Instant createdAt
) {
    public static PostSummaryResponse from(Post post) {
        return new PostSummaryResponse(
                post.getId(),
                post.getTitle(),
                post.getAuthorId(),
                post.getCategoryId(),
                post.getViewCount(),
                post.getLikeCount(),
                post.getCreatedAt()
        );
    }
}
