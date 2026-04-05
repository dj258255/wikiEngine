package com.wiki.engine.post.internal.autocomplete;

import com.wiki.engine.post.internal.cache.ConsistentHashRouter;
import com.wiki.engine.post.internal.lucene.LuceneSearchService;
import com.wiki.engine.post.internal.search.SearchLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.*;

/**
 * Redis flat KV 기반 자동완성 서비스.
 *
 * <p>Trie(9단계)를 퇴역시키고 Redis {@code prefix:v{version}:{prefix} → [top-K]} 매핑으로 전환.
 * 매시간 배치로 검색 로그 기반 prefix_topk를 빌드하여 Redis에 적재한다.
 *
 * <p>조회 경로: Redis GET O(1) → [미스] → Lucene PrefixQuery fallback (~5ms).
 * Redis 장애 시에도 Lucene fallback으로 서비스 중단 없음.
 *
 * <p>버전 네임스페이스: 새 데이터를 별도 버전 키에 적재 후 버전 포인터(단일 키)만 원자적 전환.
 * 적재 도중 실패해도 이전 버전이 유지된다 (safe rollback).
 */
@Slf4j
@Component
public class RedisAutocompleteService {

    private static final int TOPK = 10;
    private static final int MAX_PREFIX_LENGTH = 10;
    private static final int MAX_QUERIES = 10_000;
    private static final String VERSION_KEY = "prefix:current_version";
    private static final Duration KEY_TTL = Duration.ofHours(2);
    private static final long VERSION_CACHE_TTL_MS = 30_000;

    private final StringRedisTemplate redis;
    private final @Nullable ConsistentHashRouter hashRouter;
    private final JsonMapper jsonMapper;
    private final SearchLogRepository searchLogRepository;
    private final LuceneSearchService luceneSearchService;

    /** 버전 로컬 캐싱 — Redis GET 2번 → 1번으로 축소 */
    private volatile String cachedVersion;
    private volatile long versionCacheTime;

    public RedisAutocompleteService(StringRedisTemplate redis,
                                    @Nullable ConsistentHashRouter hashRouter,
                                    JsonMapper jsonMapper,
                                    SearchLogRepository searchLogRepository,
                                    LuceneSearchService luceneSearchService) {
        this.redis = redis;
        this.hashRouter = hashRouter;
        this.jsonMapper = jsonMapper;
        this.searchLogRepository = searchLogRepository;
        this.luceneSearchService = luceneSearchService;
    }

    /** 샤딩 활성화 시 ConsistentHashRouter로 라우팅, 아니면 기존 단일 Redis */
    private StringRedisTemplate redisFor(String key) {
        return hashRouter != null ? hashRouter.getNode(key) : redis;
    }

    /**
     * 앱 기동 시 Redis에 prefix_topk가 없으면 초기 빌드.
     */
    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        try {
            String version = redis.opsForValue().get(VERSION_KEY);
            if (version == null) {
                log.info("Redis에 prefix_topk 없음, 초기 빌드 시작");
                buildPrefixTopK();
            } else {
                cachedVersion = version;
                versionCacheTime = System.currentTimeMillis();
                log.info("Redis prefix_topk 존재: version={}", version);
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패, Lucene fallback으로 시작: {}", e.getMessage());
        }
    }

    /**
     * 자동완성 검색: Redis flat KV → Lucene PrefixQuery fallback.
     *
     * @param prefix 검색 접두사 (예: "삼성", "ㅅㅅ")
     * @param limit  최대 반환 수
     * @return 자동완성 결과 목록
     */
    public List<String> search(String prefix, int limit) {
        String normalized = prefix.toLowerCase();

        // 자모 포함 시 자모 분해하여 검색
        String searchKey = JamoDecomposer.containsJamo(normalized)
                ? JamoDecomposer.decompose(normalized)
                : normalized;

        // 1. Redis flat KV 조회
        try {
            String version = getCurrentVersion();
            if (version != null) {
                String key = "prefix:v" + version + ":" + searchKey;
                String json = redisFor(key).opsForValue().get(key);
                if (json != null) {
                    List<?> raw = jsonMapper.readValue(json, List.class);
                    log.info("자동완성 Redis 히트: prefix='{}', key='{}', results={}", prefix, key, raw.size());
                    return raw.stream()
                            .map(Object::toString)
                            .limit(limit)
                            .toList();
                }

                // 원본 prefix로도 시도 (자모가 아닌 완성 음절 검색)
                if (!searchKey.equals(normalized)) {
                    String originalKey = "prefix:v" + version + ":" + normalized;
                    String originalJson = redisFor(originalKey).opsForValue().get(originalKey);
                    if (originalJson != null) {
                        List<?> raw = jsonMapper.readValue(originalJson, List.class);
                        return raw.stream()
                                .map(Object::toString)
                                .limit(limit)
                                .toList();
                    }
                }
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 자동완성 조회 실패, Lucene fallback: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Redis 자동완성 역직렬화 실패, Lucene fallback: {}", e.getMessage());
        }

        // 2. Lucene PrefixQuery fallback
        try {
            List<String> fallback = luceneSearchService.autocomplete(prefix, limit);
            log.info("자동완성 Lucene fallback: prefix='{}', results={}", prefix, fallback.size());
            return fallback;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * prefix_topk 배치 빌드 — Spring Batch Job으로 전환됨 (AutocompleteBatchConfig).
     * 이 메서드는 앱 기동 시 초기화 + fallback용으로만 유지.
     */
    public void buildPrefixTopK() {
        // Spring Batch Job (AutocompleteBatchScheduler)이 매시간 실행.
        // 이 메서드는 initialize()에서 Redis에 데이터가 없을 때만 호출.
        log.info("prefix_topk 초기 빌드 위임 — Spring Batch Job이 주기적으로 갱신");
    }

    private String getCurrentVersion() {
        long now = System.currentTimeMillis();
        if (cachedVersion != null && (now - versionCacheTime) < VERSION_CACHE_TTL_MS) {
            return cachedVersion;
        }
        try {
            cachedVersion = redis.opsForValue().get(VERSION_KEY);
            versionCacheTime = now;
            return cachedVersion;
        } catch (Exception e) {
            log.warn("Redis 버전 조회 실패: {}", e.getMessage());
            return cachedVersion; // stale version as fallback
        }
    }

}
