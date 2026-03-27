package com.wiki.engine.post.internal.rag;

import java.util.List;

/**
 * RAG AI 요약 응답.
 *
 * @param summary   AI가 생성한 요약 텍스트 (출처 인용 포함)
 * @param citations 요약에서 참조한 게시글 목록 (게시글 링크용)
 */
public record RagSummaryResponse(
        String summary,
        List<RagCitation> citations
) {}
