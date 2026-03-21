package com.wiki.engine.post.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.wiki.engine.config.TieredCacheService;
import com.wiki.engine.post.PostEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 게시글 변경 이벤트를 받아 게시글 상세 캐시(L1+L2)를 무효화한다.
 *
 * <p>멱등성: evict()는 키가 없으면 no-op. 여러 번 호출해도 부작용 없음.
 *
 * <p>Phase 14-3: Kafka CDC Consumer가 활성화되면 이 핸들러는 비활성화된다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(DebeziumCdcConsumer.class)
public class CacheInvalidationEventHandler {

    private final TieredCacheService tieredCacheService;
    private final Cache<String, Object> postDetailL1Cache;

    public CacheInvalidationEventHandler(TieredCacheService tieredCacheService,
                                         @Qualifier("postDetailL1Cache") Cache<String, Object> postDetailL1Cache) {
        this.tieredCacheService = tieredCacheService;
        this.postDetailL1Cache = postDetailL1Cache;
    }

    @ApplicationModuleListener
    public void onUpdated(PostEvent.Updated event) {
        evictPostDetail(event.postId());
    }

    @ApplicationModuleListener
    public void onDeleted(PostEvent.Deleted event) {
        evictPostDetail(event.postId());
    }

    @ApplicationModuleListener
    public void onLikeChanged(PostEvent.LikeChanged event) {
        evictPostDetail(event.postId());
    }

    private void evictPostDetail(Long postId) {
        tieredCacheService.evict(postDetailL1Cache, "post:" + postId);
    }
}
