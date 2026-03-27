package com.wiki.engine.post.internal.rag;

/**
 * AI 요약에서 참조한 출처 정보.
 *
 * @param docNumber 문서 번호 ([문서 1], [문서 2] 등)
 * @param postId    게시글 ID (프론트엔드에서 /posts/{postId} 링크 생성용)
 * @param title     게시글 제목
 */
public record RagCitation(
        int docNumber,
        Long postId,
        String title
) {}
