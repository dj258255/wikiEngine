package com.wiki.engine.post.dto;

import com.wiki.engine.post.Post;

import java.time.Instant;

public record PostDetailResponse(
        Long id,
        String title,
        String content,
        Long authorId,
        Long categoryId,
        Long viewCount,
        Long likeCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static PostDetailResponse from(Post post) {
        return new PostDetailResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getAuthorId(),
                post.getCategoryId(),
                post.getViewCount(),
                post.getLikeCount(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
