package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Lucene 인덱싱 서비스.
 *
 * Post 엔티티를 Lucene Document로 변환하여 인덱스에 추가한다.
 * IndexWriter는 thread-safe이므로 동시 호출에 안전하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LuceneIndexService {

    private final IndexWriter indexWriter;
    private final SearcherManager searcherManager;
    private final PostRepository postRepository;
    private final EntityManager entityManager;

    @Value("${lucene.batch-size}")
    private int batchSize;

    /**
     * 단건 인덱싱: 게시글 생성/수정 시 호출.
     * updateDocument는 기존 문서가 있으면 삭제 후 추가한다.
     */
    public void indexPost(Post post) throws IOException {
        if (post.getContent() == null || post.getContent().isBlank()) {
            log.warn("Skipping empty content: postId={}", post.getId());
            return;
        }
        indexWriter.updateDocument(new Term("id", post.getId().toString()), toDocument(post));
        searcherManager.maybeRefresh();
    }

    /**
     * 단건 삭제: 게시글 삭제 시 호출.
     * Term("id", postId)로 해당 문서를 삭제한 뒤 NRT reader를 갱신한다.
     */
    public void deleteFromIndex(Long postId) throws IOException {
        indexWriter.deleteDocuments(new Term("id", postId.toString()));
        searcherManager.maybeRefresh();
    }

    /**
     * 전체 배치 인덱싱: posts 테이블 전체를 Lucene에 인덱싱한다.
     * cursor 기반 페이징으로 메모리 효율적으로 처리한다.
     */
    public void indexAll(long startId) throws IOException {
        long startTime = System.currentTimeMillis();
        long lastId = startId;
        long totalIndexed = 0;
        long skipped = 0;

        if (startId == 0) {
            log.info("=== Lucene 전체 인덱싱 시작 (기존 인덱스 초기화) ===");
            indexWriter.deleteAll();
            indexWriter.commit();
        } else {
            log.info("=== Lucene 인덱싱 재개 (id={} 이후부터) ===", startId);
        }

        while (true) {
            List<Post> batch = postRepository.findBatchAfterId(lastId, batchSize);
            if (batch.isEmpty()) break;

            for (Post post : batch) {
                if (post.getContent() == null || post.getContent().isBlank()) {
                    skipped++;
                    continue;
                }
                indexWriter.addDocument(toDocument(post));
                totalIndexed++;
            }

            indexWriter.commit();
            lastId = batch.getLast().getId();
            entityManager.clear();  // Hibernate 1차 캐시 해제 → GC가 Post 객체 회수 가능

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            log.info("Indexed up to id={}, total={}, skipped={}, elapsed={}s, speed={} docs/s",
                    lastId, totalIndexed, skipped, elapsed,
                    elapsed > 0 ? totalIndexed / elapsed : totalIndexed);
        }

        log.info("=== forceMerge 시작 (세그먼트 병합) ===");
        indexWriter.forceMerge(1);
        indexWriter.commit();

        long totalElapsed = (System.currentTimeMillis() - startTime) / 1000;
        log.info("=== 인덱싱 완료: total={}, skipped={}, elapsed={}s ===", totalIndexed, skipped, totalElapsed);

        searcherManager.maybeRefresh();
    }

    /**
     * Post 엔티티를 Lucene Document로 변환한다.
     *
     * - id: KeywordField (정확 매칭, 업데이트/삭제용)
     * - title: TextField (형태소 분석 + 검색 대상, stored)
     * - content: TextField (형태소 분석 + 검색 대상, not stored — 본문은 DB에서 조회)
     * - categoryId: LongField (필터링/범위 쿼리용)
     * - viewCount: LongField (stored, 조회용) + FeatureField (랭킹 부스트용)
     * - likeCount: FeatureField (랭킹 부스트용)
     * - createdAt: LongField (정렬용)
     */
    private Document toDocument(Post post) {
        Document doc = new Document();
        doc.add(new KeywordField("id", post.getId().toString(), Field.Store.YES));
        doc.add(new TextField("title", post.getTitle(), Field.Store.YES));
        doc.add(new TextField("content", post.getContent(), Field.Store.NO));

        if (post.getCategoryId() != null) {
            doc.add(new LongField("categoryId", post.getCategoryId(), Field.Store.YES));
        }
        doc.add(new LongField("viewCount", post.getViewCount(), Field.Store.YES));
        doc.add(new LongField("createdAt", post.getCreatedAt().toEpochMilli(), Field.Store.YES));

        // FeatureField: 인기도 랭킹 부스트 (BlockMaxWAND 호환, saturation 함수 사용)
        // FeatureField 값은 0보다 커야 하므로 최소 1로 보정
        doc.add(new FeatureField("features", "viewCount", Math.max(post.getViewCount(), 1)));
        doc.add(new FeatureField("features", "likeCount", Math.max(post.getLikeCount(), 1)));

        return doc;
    }
}
