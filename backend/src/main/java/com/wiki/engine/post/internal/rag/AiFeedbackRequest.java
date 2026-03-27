package com.wiki.engine.post.internal.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AI 요약 피드백 요청 DTO.
 *
 * @param query    검색어
 * @param rating   1=thumbs_up, -1=thumbs_down
 * @param category 부정 피드백 사유 (thumbs_down일 때만, 선택)
 * @param comment  자유 텍스트 코멘트 (선택)
 */
public record AiFeedbackRequest(
        @NotBlank String query,
        @NotNull Integer rating,
        String category,
        String comment
) {}
