package com.wiki.engine.post.internal.cache;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * L1(Caffeine) + L2(Redis) 2계층 캐시.
 *
 * <p>조회 순서: L1 → L2 → Origin(DB/Lucene).
 * L2 히트 시 L1에 승격, Origin 조회 시 양쪽에 저장.
 * Redis 장애 시 L1+Origin fallback (서비스 중단 없음).
 */
@Component
public class TieredCacheService {

    private static final Logger log = LoggerFactory.getLogger(TieredCacheService.class);

    private final StringRedisTemplate redis;
    private final @Nullable ConsistentHashRouter hashRouter;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    public TieredCacheService(StringRedisTemplate redis,
                              @Nullable ConsistentHashRouter hashRouter,
                              JsonMapper jsonMapper,
                              MeterRegistry meterRegistry) {
        this.redis = redis;
        this.hashRouter = hashRouter;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
    }

    /** 샤딩 활성화 시 ConsistentHashRouter로 라우팅, 아니면 기존 단일 Redis */
    private StringRedisTemplate redisFor(String key) {
        return hashRouter != null ? hashRouter.getNode(key) : redis;
    }

    /**
     * 2계층 캐시 조회.
     *
     * @param region   캐시 영역 (Grafana 메트릭 구분용: "searchResults", "postDetail" 등)
     * @param l1Cache  Caffeine 캐시 인스턴스
     * @param redisKey Redis 키 (예: "search:keyword:0:20")
     * @param type     역직렬화 대상 타입
     * @param l2Ttl    Redis TTL (빈 결과는 자동으로 1/5 TTL 적용 — negative caching)
     * @param loader   캐시 미스 시 원본 데이터 로더
     */
    public <T> T get(String region,
                     Cache<String, Object> l1Cache,
                     String redisKey,
                     Class<T> type,
                     Duration l2Ttl,
                     Supplier<T> loader) {

        // 1. L1 확인
        Object cached = l1Cache.getIfPresent(redisKey);
        if (cached != null && type.isInstance(cached)) {
            meterRegistry.counter("tiered_cache", "region", region, "level", "L1").increment();
            return type.cast(cached);
        }

        // 2. L2 확인 (Redis 장애 시 스킵)
        try {
            String json = redisFor(redisKey).opsForValue().get(redisKey);
            if (json != null) {
                T value = jsonMapper.readValue(json, type);
                l1Cache.put(redisKey, value);
                meterRegistry.counter("tiered_cache", "region", region, "level", "L2").increment();
                return value;
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis L2 조회 실패 ({}), L1+DB fallback: {}", redisKey, e.getMessage());
        } catch (Exception e) {
            log.warn("Redis L2 역직렬화 실패 ({}): {}", redisKey, e.getMessage());
        }

        // 3. Origin 조회
        T value = loader.get();

        // 4. L1 + L2 양쪽에 저장
        // 빈 결과(negative cache)는 짧은 TTL — 인덱스 로딩 지연/일시적 장애 시 빠른 복구
        Duration effectiveTtl = isEmpty(value) ? Duration.ofSeconds(30) : l2Ttl;
        l1Cache.put(redisKey, value);
        try {
            redisFor(redisKey).opsForValue().set(redisKey, jsonMapper.writeValueAsString(value), effectiveTtl);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis L2 저장 실패 ({}), L1에만 캐싱: {}", redisKey, e.getMessage());
        } catch (Exception e) {
            log.warn("Redis L2 직렬화 실패 ({}): {}", redisKey, e.getMessage());
        }

        meterRegistry.counter("tiered_cache", "region", region, "level", "origin").increment();
        return value;
    }

    /**
     * 결과가 비어있는지 판단 (negative caching용).
     * CachedSearchResult의 content가 비어있으면 빈 결과로 간주.
     */
    private boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof com.wiki.engine.post.dto.CachedSearchResult cached) {
            return cached.content() == null || cached.content().isEmpty();
        }
        return false;
    }

    /**
     * L1 + L2 양쪽에서 캐시 무효화.
     */
    public void evict(Cache<String, Object> l1Cache, String redisKey) {
        l1Cache.invalidate(redisKey);
        try {
            redisFor(redisKey).delete(redisKey);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis L2 삭제 실패 ({}): {}", redisKey, e.getMessage());
        }
    }
}
