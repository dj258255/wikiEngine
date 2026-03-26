package com.wiki.engine.post.dto;

import org.springframework.data.domain.Slice;

import java.util.Map;

/**
 * 검색 결과 + 오타 교정 제안 + 카테고리 Facet을 함께 반환하는 DTO.
 *
 * @param results        검색 결과 (Slice)
 * @param suggestion     오타 교정 제안 ("혹시 OO을 찾으셨나요?"). null이면 교정 없음.
 * @param categoryFacets 카테고리별 매칭 건수 (Phase 19.2). 재색인 전이면 빈 맵.
 */
public record SearchResponseWithSuggestion(
        Slice<PostSearchResponse> results,
        String suggestion,
        Map<String, Long> categoryFacets
) {}
