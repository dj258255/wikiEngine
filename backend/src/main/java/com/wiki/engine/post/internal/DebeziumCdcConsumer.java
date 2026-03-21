package com.wiki.engine.post.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.wiki.engine.config.TieredCacheService;
import com.wiki.engine.post.Post;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Debezium CDC Consumer — MySQL binlog 변경 이벤트를 Kafka에서 소비하여
 * Lucene 인덱스와 캐시를 갱신한다.
 *
 * <p>Phase 14-3: @ApplicationModuleListener(앱 이벤트 기반)에서
 * Debezium + Kafka(binlog 기반 CDC)로 전환.
 *
 * <p>Debezium이 발행하는 메시지 구조:
 * <pre>
 * {
 *   "payload": {
 *     "before": { ... },       // 변경 전 행 (UPDATE/DELETE)
 *     "after": { ... },        // 변경 후 행 (INSERT/UPDATE)
 *     "op": "c|u|d|r",         // c=create, u=update, d=delete, r=read(snapshot)
 *     "source": { "ts_ms": ..., "table": "posts", ... }
 *   }
 * }
 * </pre>
 *
 * <p>멱등성: Lucene updateDocument()는 자연 멱등, 캐시 evict()는 no-op 안전.
 * Kafka Consumer는 at-least-once이므로 중복 메시지가 올 수 있지만 안전하다.
 *
 * <p>활성화 조건: spring.kafka.bootstrap-servers가 설정된 경우에만 Bean 등록.
 * Kafka가 없는 환경(로컬 개발 등)에서는 기존 @ApplicationModuleListener가 동작.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class DebeziumCdcConsumer {

    private final LuceneIndexService luceneIndexService;
    private final PostRepository postRepository;
    private final TieredCacheService tieredCacheService;
    private final Cache<String, Object> postDetailL1Cache;
    private final Cache<String, Object> searchResultsL1Cache;
    private final JsonMapper jsonMapper;

    public DebeziumCdcConsumer(LuceneIndexService luceneIndexService,
                               PostRepository postRepository,
                               TieredCacheService tieredCacheService,
                               @Qualifier("postDetailL1Cache") Cache<String, Object> postDetailL1Cache,
                               @Qualifier("searchResultsL1Cache") Cache<String, Object> searchResultsL1Cache,
                               JsonMapper jsonMapper) {
        this.luceneIndexService = luceneIndexService;
        this.postRepository = postRepository;
        this.tieredCacheService = tieredCacheService;
        this.postDetailL1Cache = postDetailL1Cache;
        this.searchResultsL1Cache = searchResultsL1Cache;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Debezium CDC 이벤트를 소비한다.
     * 토픽 이름: {debezium.server-name}.{schema}.posts
     * 환경변수 CDC_TOPIC으로 오버라이드 가능.
     */
    @KafkaListener(topics = "${cdc.topic:dbserver1.wikidb.posts}")
    public void onPostChange(String message) {
        try {
            JsonNode root = jsonMapper.readTree(message);
            // schemas.enable=false이므로 payload 래퍼 없이 root에 바로 before/after/op 존재
            String op = root.path("op").textValue();
            if (op == null) return;
            switch (op) {
                case "c", "r" -> handleCreate(root);   // create or snapshot read
                case "u" -> handleUpdate(root);
                case "d" -> handleDelete(root);
                default -> log.debug("CDC: 무시하는 op 타입: {}", op);
            }
        } catch (Exception e) {
            log.error("CDC 메시지 처리 실패: {}", message, e);
            // at-least-once: 예외를 던지지 않으면 offset이 커밋됨
            // 실패한 메시지는 로그로 남기고 다음 메시지 처리 계속
        }
    }

    private void handleCreate(JsonNode root) {
        JsonNode after = root.path("after");
        if (after.isMissingNode()) return;
        long postId = after.path("id").longValue();
        if (postId == 0) return;

        postRepository.findById(postId).ifPresent(post -> {
            indexSafely(post);
            searchResultsL1Cache.invalidateAll();
            log.info("CDC CREATE: postId={}", postId);
        });
    }

    private void handleUpdate(JsonNode root) {
        JsonNode after = root.path("after");
        if (after.isMissingNode()) return;
        long postId = after.path("id").longValue();
        if (postId == 0) return;

        postRepository.findById(postId).ifPresent(post -> {
            indexSafely(post);
            tieredCacheService.evict(postDetailL1Cache, "post:" + postId);
            searchResultsL1Cache.invalidateAll();
            log.info("CDC UPDATE: postId={}", postId);
        });
    }

    private void handleDelete(JsonNode root) {
        JsonNode before = root.path("before");
        if (before.isMissingNode()) return;
        long postId = before.path("id").longValue();
        if (postId == 0) return;

        try {
            luceneIndexService.deleteFromIndex(postId);
        } catch (IOException e) {
            log.error("CDC Lucene 삭제 실패: postId={}", postId, e);
        }
        tieredCacheService.evict(postDetailL1Cache, "post:" + postId);
        searchResultsL1Cache.invalidateAll();
        log.info("CDC DELETE: postId={}", postId);
    }

    private void indexSafely(Post post) {
        try {
            luceneIndexService.indexPost(post);
        } catch (IOException e) {
            log.error("CDC Lucene 색인 실패: postId={}", post.getId(), e);
        }
    }
}
