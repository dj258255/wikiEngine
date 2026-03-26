package com.wiki.engine.post.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.wiki.engine.post.PostEvent;
import com.wiki.engine.post.internal.cdc.DebeziumCdcConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 게시글 변경 이벤트를 받아 검색 결과 캐시를 무효화한다.
 *
 * <p>L1(Caffeine): invalidateAll()로 즉시 무효화.
 * L2(Redis): TTL(10분)에 의한 자연 만료. 키 패턴("search:*") 삭제는
 * KEYS/SCAN 비용이 크므로 Phase 14-1에서는 TTL에 위임한다.
 *
 * <p>기존(Phase 13): 검색 캐시 무효화 없음 → 영구 stale.
 * Phase 14-1: L1 즉시 무효화 + L2 최대 10분 stale.
 *
 * <p>멱등성: invalidateAll()은 여러 번 호출해도 동일.
 *
 * <p>Phase 14-3: Kafka CDC Consumer가 활성화되면 이 핸들러는 비활성화된다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(DebeziumCdcConsumer.class)
public class SearchCacheEventHandler {

    private final Cache<String, Object> searchResultsL1Cache;

    public SearchCacheEventHandler(
            @Qualifier("searchResultsL1Cache") Cache<String, Object> searchResultsL1Cache) {
        this.searchResultsL1Cache = searchResultsL1Cache;
    }

    @ApplicationModuleListener
    public void onCreated(PostEvent.Created event) {
        invalidateSearchCache();
    }

    @ApplicationModuleListener
    public void onUpdated(PostEvent.Updated event) {
        invalidateSearchCache();
    }

    @ApplicationModuleListener
    public void onDeleted(PostEvent.Deleted event) {
        invalidateSearchCache();
    }

    private void invalidateSearchCache() {
        searchResultsL1Cache.invalidateAll();
    }
}
