package com.wiki.engine.post.dto;

import java.util.List;

/**
 * 검색 결과 캐시용 래퍼.
 * Slice는 Spring Data 인터페이스라 JSON 역직렬화가 복잡하므로,
 * content + hasNext만 캐싱하고 SliceImpl으로 재구성한다.
 */
public record CachedSearchResult(List<PostSearchResponse> content, boolean hasNext) {
}
