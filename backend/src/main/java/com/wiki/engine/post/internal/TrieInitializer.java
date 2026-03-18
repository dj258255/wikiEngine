package com.wiki.engine.post.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Trie 초기화 — Phase 11에서 퇴역.
 *
 * <p>자동완성이 Redis flat KV(RedisAutocompleteService)로 전환되어
 * Trie rebuild가 더 이상 필요하지 않다. 빈은 유지하되 스케줄/이벤트 리스너를 제거한다.
 *
 * @deprecated Phase 11에서 RedisAutocompleteService로 대체됨
 */
@Deprecated
@Slf4j
@Component
@RequiredArgsConstructor
public class TrieInitializer {

    private final AutocompleteTrie trie;
}
