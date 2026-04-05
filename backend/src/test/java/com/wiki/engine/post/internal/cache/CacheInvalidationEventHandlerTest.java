package com.wiki.engine.post.internal.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.wiki.engine.post.Post;
import com.wiki.engine.post.PostEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationEventHandlerTest {

    @Mock private TieredCacheService tieredCacheService;
    @Mock private Cache<String, Object> postDetailL1Cache;

    @Test
    @DisplayName("[해피] Updated → 게시글 상세 캐시 무효화")
    void onUpdated() {
        var handler = new CacheInvalidationEventHandler(tieredCacheService, postDetailL1Cache);
        Post post = Post.builder().title("t").content("c").authorId(1L).build();

        handler.onUpdated(new PostEvent.Updated(42L, post));

        verify(tieredCacheService).evict(postDetailL1Cache, "post:42");
    }

    @Test
    @DisplayName("[해피] Deleted → 게시글 상세 캐시 무효화")
    void onDeleted() {
        var handler = new CacheInvalidationEventHandler(tieredCacheService, postDetailL1Cache);

        handler.onDeleted(new PostEvent.Deleted(42L));

        verify(tieredCacheService).evict(postDetailL1Cache, "post:42");
    }

    @Test
    @DisplayName("[해피] LikeChanged → 게시글 상세 캐시 무효화")
    void onLikeChanged() {
        var handler = new CacheInvalidationEventHandler(tieredCacheService, postDetailL1Cache);

        handler.onLikeChanged(new PostEvent.LikeChanged(42L));

        verify(tieredCacheService).evict(postDetailL1Cache, "post:42");
    }
}
