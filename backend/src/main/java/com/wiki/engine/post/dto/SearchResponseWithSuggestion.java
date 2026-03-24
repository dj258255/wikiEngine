package com.wiki.engine.post.dto;

import org.springframework.data.domain.Slice;

/**
 * 검색 결과 + 오타 교정 제안을 함께 반환하는 DTO.
 *
 * @param results    검색 결과 (Slice)
 * @param suggestion 오타 교정 제안 ("혹시 OO을 찾으셨나요?"). null이면 교정 없음.
 */
public record SearchResponseWithSuggestion(
        Slice<PostSearchResponse> results,
        String suggestion
) {}
