package com.wiki.engine.post.dto;

/**
 * 좋아요/좋아요 취소 후 현재 상태 응답.
 *
 * @param likeCount 현재 좋아요 수
 * @param liked 요청한 사용자의 좋아요 여부
 */
public record LikeResponse(
        long likeCount,
        boolean liked
) {
}
