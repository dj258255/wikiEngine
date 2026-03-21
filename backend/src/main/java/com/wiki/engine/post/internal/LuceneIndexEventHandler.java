package com.wiki.engine.post.internal;

import com.wiki.engine.post.PostEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 게시글 변경 이벤트를 받아 Lucene 인덱스를 갱신한다.
 *
 * <p>멱등성: IndexWriter.updateDocument()는 Term 기준 삭제 후 재삽입이므로 자연 멱등.
 * deleteFromIndex()도 없는 문서 삭제 시 no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuceneIndexEventHandler {

    private final LuceneIndexService luceneIndexService;

    @ApplicationModuleListener
    public void onCreated(PostEvent.Created event) {
        indexSafely(event);
    }

    @ApplicationModuleListener
    public void onUpdated(PostEvent.Updated event) {
        indexSafely(event);
    }

    @ApplicationModuleListener
    public void onDeleted(PostEvent.Deleted event) {
        try {
            luceneIndexService.deleteFromIndex(event.postId());
        } catch (IOException e) {
            log.error("Lucene 삭제 실패: postId={}", event.postId(), e);
        }
    }

    private void indexSafely(PostEvent event) {
        try {
            if (event instanceof PostEvent.Created c) {
                luceneIndexService.indexPost(c.post());
            } else if (event instanceof PostEvent.Updated u) {
                luceneIndexService.indexPost(u.post());
            }
        } catch (IOException e) {
            log.error("Lucene 색인 실패: postId={}", event.postId(), e);
        }
    }
}
