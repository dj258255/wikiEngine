package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class LuceneIndexService {

    private final IndexWriter indexWriter;  // null in replica mode
    private final SearcherManager searcherManager;
    private final PostRepository postRepository;
    private final EntityManager entityManager;

    @Value("${lucene.batch-size}")
    private int batchSize;

    public LuceneIndexService(
            @Autowired(required = false) IndexWriter indexWriter,
            SearcherManager searcherManager,
            PostRepository postRepository,
            EntityManager entityManager) {
        this.indexWriter = indexWriter;
        this.searcherManager = searcherManager;
        this.postRepository = postRepository;
        this.entityManager = entityManager;
    }

    /**
     * 단건 인덱싱: 게시글 생성/수정 시 호출.
     * updateDocument는 기존 문서가 있으면 삭제 후 추가한다.
     */
    public void indexPost(Post post) throws IOException {
        if (indexWriter == null) {
            log.debug("Lucene replica mode — skipping index for post {}", post.getId());
            return;
        }
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
        if (indexWriter == null) {
            log.debug("Lucene replica mode — skipping delete for post {}", postId);
            return;
        }
        indexWriter.deleteDocuments(new Term("id", postId.toString()));
        searcherManager.maybeRefresh();
    }

    private static final int COMMIT_INTERVAL = 1_000_000;
    private static final int LOG_INTERVAL = 100_000;

    /**
     * 전체 배치 인덱싱: posts 테이블 전체를 Lucene에 인덱싱한다.
     * cursor 기반 페이징으로 메모리 효율적으로 처리한다.
     *
     * 성능 최적화:
     * - commit()을 100만 건마다 1회로 제한 (매 배치마다 하면 fsync 병목)
     * - forceMerge(5)로 세그먼트 병합 시간 단축
     */
    public void indexAll(long startId) throws IOException {
        if (indexWriter == null) {
            log.warn("Lucene replica mode — indexAll is not available");
            return;
        }
        long startTime = System.currentTimeMillis();
        long lastId = startId;
        long totalIndexed = 0;
        long skipped = 0;
        long lastCommitCount = 0;

        if (startId == 0) {
            log.info("=== Lucene 전체 인덱싱 시작 (기존 인덱스 초기화) ===");
            indexWriter.deleteAll();
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

            lastId = batch.getLast().getId();
            entityManager.clear();

            // 100만 건마다 commit (크래시 복구용 체크포인트)
            if (totalIndexed - lastCommitCount >= COMMIT_INTERVAL) {
                indexWriter.commit();
                lastCommitCount = totalIndexed;
                log.info("Checkpoint commit at {} docs", totalIndexed);
            }

            // 10만 건마다 진행 로그
            if (totalIndexed % LOG_INTERVAL < batchSize) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                log.info("Indexed up to id={}, total={}, skipped={}, elapsed={}s, speed={} docs/s",
                        lastId, totalIndexed, skipped, elapsed,
                        elapsed > 0 ? totalIndexed / elapsed : totalIndexed);
            }
        }

        log.info("=== 최종 commit ===");
        indexWriter.commit();

        log.info("=== forceMerge 시작 (세그먼트 병합) ===");
        indexWriter.forceMerge(5);
        indexWriter.commit();

        long totalElapsed = (System.currentTimeMillis() - startTime) / 1000;
        log.info("=== 인덱싱 완료: total={}, skipped={}, elapsed={}s ===", totalIndexed, skipped, totalElapsed);

        searcherManager.maybeRefresh();
    }

    private static final int SNIPPET_SOURCE_LENGTH = 500;

    /**
     * Post 엔티티를 Lucene Document로 변환한다.
     *
     * - id: KeywordField (정확 매칭, 업데이트/삭제용)
     * - title: TextField (형태소 분석 + 검색 대상, stored)
     * - content: TextField (형태소 분석 + 검색 대상, not stored — 본문은 DB에서 조회)
     * - snippetSource: StoredField (앞 500자, UnifiedHighlighter용 — Phase 18)
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

        // Phase 18: snippet용 본문 앞 500자 저장 (UnifiedHighlighter 용)
        // content 전체를 Store.YES로 하면 인덱스 100GB+ 폭증하므로, 앞 500자만 별도 저장
        String content = post.getContent();
        if (content != null && !content.isBlank()) {
            String snippetSource = content.length() <= SNIPPET_SOURCE_LENGTH
                    ? content
                    : content.substring(0, SNIPPET_SOURCE_LENGTH);
            doc.add(new TextField("snippetSource", snippetSource, Field.Store.YES));
        }

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
