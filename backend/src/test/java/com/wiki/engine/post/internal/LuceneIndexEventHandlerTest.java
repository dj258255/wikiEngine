package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import com.wiki.engine.post.PostEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LuceneIndexEventHandlerTest {

    @InjectMocks
    private LuceneIndexEventHandler handler;

    @Mock
    private LuceneIndexService luceneIndexService;

    private Post createTestPost() {
        return Post.builder()
                .title("테스트 게시글")
                .content("본문")
                .authorId(1L)
                .categoryId(1L)
                .build();
    }

    @Test
    @DisplayName("[해피] Created 이벤트 → Lucene 인덱싱")
    void onCreated() throws IOException {
        Post post = createTestPost();

        handler.onCreated(new PostEvent.Created(1L, post));

        verify(luceneIndexService).indexPost(post);
    }

    @Test
    @DisplayName("[해피] Updated 이벤트 → Lucene 재인덱싱")
    void onUpdated() throws IOException {
        Post post = createTestPost();

        handler.onUpdated(new PostEvent.Updated(1L, post));

        verify(luceneIndexService).indexPost(post);
    }

    @Test
    @DisplayName("[해피] Deleted 이벤트 → Lucene 삭제")
    void onDeleted() throws IOException {
        handler.onDeleted(new PostEvent.Deleted(1L));

        verify(luceneIndexService).deleteFromIndex(1L);
    }

    @Test
    @DisplayName("[코너] Lucene 색인 실패해도 예외가 전파되지 않는다")
    void indexFailureDoesNotPropagate() throws IOException {
        Post post = createTestPost();
        willThrow(new IOException("index error")).given(luceneIndexService).indexPost(post);

        // 예외 없이 정상 종료
        handler.onCreated(new PostEvent.Created(1L, post));
    }

    @Test
    @DisplayName("[코너] Lucene 삭제 실패해도 예외가 전파되지 않는다")
    void deleteFailureDoesNotPropagate() throws IOException {
        willThrow(new IOException("delete error")).given(luceneIndexService).deleteFromIndex(1L);

        // 예외 없이 정상 종료
        handler.onDeleted(new PostEvent.Deleted(1L));
    }
}
