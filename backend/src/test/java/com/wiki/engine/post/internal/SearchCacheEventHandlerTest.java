package com.wiki.engine.post.internal;

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
class SearchCacheEventHandlerTest {

    @Mock private Cache<String, Object> searchResultsL1Cache;

    @Test
    @DisplayName("[해피] Created → 검색 캐시 L1 전체 무효화")
    void onCreated() {
        var handler = new SearchCacheEventHandler(searchResultsL1Cache);
        Post post = Post.builder().title("t").content("c").authorId(1L).build();

        handler.onCreated(new PostEvent.Created(1L, post));

        verify(searchResultsL1Cache).invalidateAll();
    }

    @Test
    @DisplayName("[해피] Updated → 검색 캐시 L1 전체 무효화")
    void onUpdated() {
        var handler = new SearchCacheEventHandler(searchResultsL1Cache);
        Post post = Post.builder().title("t").content("c").authorId(1L).build();

        handler.onUpdated(new PostEvent.Updated(1L, post));

        verify(searchResultsL1Cache).invalidateAll();
    }

    @Test
    @DisplayName("[해피] Deleted → 검색 캐시 L1 전체 무효화")
    void onDeleted() {
        var handler = new SearchCacheEventHandler(searchResultsL1Cache);

        handler.onDeleted(new PostEvent.Deleted(1L));

        verify(searchResultsL1Cache).invalidateAll();
    }
}
