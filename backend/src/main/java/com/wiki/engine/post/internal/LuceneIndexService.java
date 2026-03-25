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

import org.apache.lucene.index.LiveIndexWriterConfig;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final double BULK_RAM_BUFFER_MB = 512.0;
    private static final int INDEX_THREADS = Runtime.getRuntime().availableProcessors();
    private static final List<Post> POISON_PILL = List.of();

    /**
     * 전체 배치 인덱싱: posts 테이블 전체를 Lucene에 인덱싱한다.
     *
     * 성능 최적화:
     * - RAM buffer 512MB (bulk 중 flush 빈도 대폭 감소)
     * - Producer-Consumer 파이프라인: DB 읽기와 Lucene 쓰기를 동시 실행
     * - commit()을 100만 건마다 1회로 제한 (매 배치마다 하면 fsync 병목)
     * - forceMerge(5)로 세그먼트 병합 시간 단축
     */
    public void indexAll(long startId) throws IOException {
        if (indexWriter == null) {
            log.warn("Lucene replica mode — indexAll is not available");
            return;
        }

        // RAM buffer 확대 (bulk 인덱싱 동안만)
        LiveIndexWriterConfig config = indexWriter.getConfig();
        double originalRamBuffer = config.getRAMBufferSizeMB();
        config.setRAMBufferSizeMB(BULK_RAM_BUFFER_MB);
        log.info("RAM buffer: {}MB → {}MB (bulk mode)", originalRamBuffer, BULK_RAM_BUFFER_MB);

        try {
            doPipelinedIndexAll(startId);
        } finally {
            config.setRAMBufferSizeMB(originalRamBuffer);
            log.info("RAM buffer 복원: {}MB", originalRamBuffer);
        }
    }

    private void doPipelinedIndexAll(long startId) throws IOException {
        long startTime = System.currentTimeMillis();

        if (startId == 0) {
            log.info("=== Lucene 전체 인덱싱 시작 (기존 인덱스 초기화) ===");
            indexWriter.deleteAll();
            indexWriter.commit(); // 멀티스레드 인덱싱 전에 깨끗한 상태 확보
        } else {
            log.info("=== Lucene 인덱싱 재개 (id={} 이후부터) ===", startId);
        }

        // Producer-Consumer: capacity 2 = 더블 버퍼링 (DB가 다음 배치 읽는 동안 Lucene이 현재 배치 인덱싱)
        var queue = new ArrayBlockingQueue<List<Post>>(2);
        var producerLastId = new AtomicLong(startId);
        var producerError = new AtomicLong(-1); // -1 = no error

        // Producer: DB에서 배치를 읽어 큐에 넣는다
        Thread producer = Thread.startVirtualThread(() -> {
            long lastId = startId;
            try {
                while (true) {
                    List<Post> batch = postRepository.findBatchAfterId(lastId, batchSize);
                    if (batch.isEmpty()) break;
                    lastId = batch.getLast().getId();
                    producerLastId.set(lastId);
                    queue.put(batch);    // 큐가 차 있으면 Lucene이 소비할 때까지 대기
                    entityManager.clear();
                }
            } catch (Exception e) {
                log.error("DB Producer 오류", e);
                producerError.set(lastId);
            } finally {
                try { queue.put(POISON_PILL); } catch (InterruptedException ignored) {}
            }
        });

        // Consumer: 큐에서 배치를 꺼내 멀티스레드로 Lucene에 인덱싱
        // IndexWriter.addDocument()는 thread-safe — 스레드별 DocumentsWriterPerThread로 병렬 분석
        log.info("인덱싱: Virtual Thread (available processors={})", INDEX_THREADS);
        ExecutorService indexPool = Executors.newVirtualThreadPerTaskExecutor();
        var totalIndexed = new AtomicLong(0);
        var skipped = new AtomicLong(0);
        long lastCommitCount = 0;

        while (true) {
            List<Post> batch;
            try {
                batch = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (batch == POISON_PILL) break;

            // 배치를 스레드풀에서 병렬 처리 (Nori 형태소 분석이 CPU 병목이므로 코어 수만큼 병렬화)
            var latch = new java.util.concurrent.CountDownLatch(batch.size());
            for (Post post : batch) {
                indexPool.submit(() -> {
                    try {
                        if (post.getContent() == null || post.getContent().isBlank()) {
                            skipped.incrementAndGet();
                            return;
                        }
                        indexWriter.addDocument(toDocument(post));
                        totalIndexed.incrementAndGet();
                    } catch (IOException e) {
                        log.error("인덱싱 실패: postId={}", post.getId(), e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try { latch.await(); } catch (InterruptedException ignored) {}

            // 100만 건마다 commit (크래시 복구용 체크포인트)
            long currentTotal = totalIndexed.get();
            if (currentTotal - lastCommitCount >= COMMIT_INTERVAL) {
                indexWriter.commit();
                lastCommitCount = currentTotal;
                log.info("Checkpoint commit at {} docs", currentTotal);
            }

            // 10만 건마다 진행 로그
            if (currentTotal % LOG_INTERVAL < batchSize) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                log.info("Indexed up to id={}, total={}, skipped={}, elapsed={}s, speed={} docs/s",
                        producerLastId.get(), currentTotal, skipped.get(), elapsed,
                        elapsed > 0 ? currentTotal / elapsed : currentTotal);
            }
        }

        indexPool.shutdown();
        try { indexPool.awaitTermination(1, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}

        // Producer 스레드 종료 대기
        try { producer.join(); } catch (InterruptedException ignored) {}

        if (producerError.get() >= 0) {
            log.error("Producer 오류 발생 — 인덱싱 중단됨. 마지막 성공 id={}", producerError.get());
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
