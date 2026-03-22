package com.wiki.engine.post.internal;

import com.wiki.engine.config.ConsistentHashRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
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
            return luceneSearchService.autocomplete(prefix, limit);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * prefix_topk 배치 빌드 — 매시간 실행.
     *
     * <p>흐름: SQL GROUP BY → prefix 분해 (원본 + 자모 + 초성) → Redis 적재 → 버전 포인터 전환.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional(readOnly = true)
    public void buildPrefixTopK() {
        long start = System.nanoTime();

        List<Object[]> topQueries = searchLogRepository.findTopQueriesSince(
                LocalDateTime.now().minusDays(7), MAX_QUERIES);

        if (topQueries.isEmpty()) {
            log.info("prefix_topk 빌드 스킵: 검색 로그 없음");
            return;
        }

        // prefix → top-K 매핑 구성
        Map<String, PriorityQueue<ScoredQuery>> prefixMap = new HashMap<>();

        for (Object[] row : topQueries) {
            String query = (String) row[0];
            long count = ((Number) row[1]).longValue();
            if (query == null || query.isBlank()) continue;

            String normalized = query.toLowerCase();

            // 1. 원본 prefix 분해
            addPrefixes(prefixMap, normalized, normalized, count);

            // 2. 자모 분해 prefix
            String decomposed = JamoDecomposer.decompose(normalized);
            addPrefixes(prefixMap, decomposed, normalized, count);

            // 3. 초성 prefix (2자 이상)
            String choseong = JamoDecomposer.extractChoseong(normalized);
            if (choseong.length() >= 2) {
                addPrefixes(prefixMap, choseong, normalized, count);
            }
        }

        // 새 버전 네임스페이스에 적재
        long newVersion = System.currentTimeMillis();
        int keyCount = 0;

        for (var entry : prefixMap.entrySet()) {
            List<String> topK = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(ScoredQuery::score).reversed())
                    .map(ScoredQuery::query)
                    .toList();
            try {
                String key = "prefix:v" + newVersion + ":" + entry.getKey();
                redisFor(key).opsForValue().set(key, jsonMapper.writeValueAsString(topK), KEY_TTL);
                keyCount++;
            } catch (Exception e) {
                log.error("prefix_topk Redis 저장 실패: prefix={}", entry.getKey(), e);
            }
        }

        // 버전 포인터 원자적 전환
        redis.opsForValue().set(VERSION_KEY, String.valueOf(newVersion));
        cachedVersion = String.valueOf(newVersion);
        versionCacheTime = System.currentTimeMillis();

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("prefix_topk 갱신 완료: version={}, keys={}, 소스 쿼리={}, {}ms",
                newVersion, keyCount, topQueries.size(), elapsed);
    }

    private void addPrefixes(Map<String, PriorityQueue<ScoredQuery>> prefixMap,
                             String text, String originalQuery, long count) {
        for (int len = 1; len <= Math.min(text.length(), MAX_PREFIX_LENGTH); len++) {
            String prefix = text.substring(0, len);
            PriorityQueue<ScoredQuery> heap = prefixMap.computeIfAbsent(prefix,
                    k -> new PriorityQueue<>(Comparator.comparingLong(ScoredQuery::score)));

            // 같은 originalQuery가 이미 힙에 있으면 스킵
            boolean exists = heap.stream().anyMatch(sq -> sq.query().equals(originalQuery));
            if (!exists) {
                heap.offer(new ScoredQuery(originalQuery, count));
                if (heap.size() > TOPK) {
                    heap.poll();
                }
            }
        }
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

    private record ScoredQuery(String query, long score) {
    }
}
