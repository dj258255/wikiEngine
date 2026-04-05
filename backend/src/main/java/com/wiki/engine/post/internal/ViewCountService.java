package com.wiki.engine.post.internal;

import com.wiki.engine.post.internal.cache.ConsistentHashRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Redis 기반 조회수 카운터 (INCR + 배치 flush).
 *
 * GET /posts/{id} 요청 시 DB UPDATE 대신 Redis INCR로 즉시 반환.
 * 30초마다 누적된 조회수를 DB에 배치 flush.
 *
 * 장점:
 * - GET 요청에서 DB 쓰기 제거 → R/W 분리 라우팅 문제 해결
 * - Redis INCR은 싱글스레드 원자적 연산 → 동시성 문제 없음
 * - Row Lock 경합 제거 → DB 부하 감소
 *
 * 트레이드오프:
 * - Redis 장애 시 최대 30초 조회수 유실 (커뮤니티에서 허용 가능)
 * - 조회수가 최대 30초 지연 반영 (Eventual Consistency)
 */
@Slf4j
@Service
public class ViewCountService {

    private final StringRedisTemplate redisTemplate;
    private final @Nullable ConsistentHashRouter hashRouter;
    private final PostRepository postRepository;

    private static final String KEY_PREFIX = "post:views:";

    public ViewCountService(StringRedisTemplate redisTemplate,
                            @Nullable ConsistentHashRouter hashRouter,
                            PostRepository postRepository) {
        this.redisTemplate = redisTemplate;
        this.hashRouter = hashRouter;
        this.postRepository = postRepository;
    }

    private StringRedisTemplate redisFor(String key) {
        return hashRouter != null ? hashRouter.getNode(key) : redisTemplate;
    }

    /**
     * 조회수 1 증가 (Redis INCR, O(1), ~0.1ms).
     * DB를 타지 않으므로 R/W 라우팅과 무관.
     */
    public void increment(Long postId) {
        String key = KEY_PREFIX + postId;
        try {
            redisFor(key).opsForValue().increment(key);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 조회수 INCR 실패 (무시): postId={}", postId);
        }
    }

    /**
     * 누적된 조회수를 DB에 배치 flush (30초 주기).
     * Redis에서 읽고 삭제 → DB UPDATE.
     *
     * KEYS → SCAN 전환: KEYS는 전체 keyspace를 O(N) 블로킹 스캔하여
     * 실행 동안 모든 Redis 명령이 대기한다. SCAN은 커서 기반으로
     * 각 호출 사이에 다른 명령이 실행될 수 있어 블로킹하지 않는다.
     */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void flushToDB() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(1000)
                .build();

        int flushed = 0;

        // 샤딩 시 모든 노드를 순회, 아니면 단일 Redis만
        List<StringRedisTemplate> targets = hashRouter != null
                ? hashRouter.getAllNodes()
                : List.of(redisTemplate);

        for (StringRedisTemplate node : targets) {
            try (Cursor<String> cursor = node.scan(options)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    try {
                        String value = node.opsForValue().getAndDelete(key);
                        if (value == null) continue;

                        long delta = Long.parseLong(value);
                        if (delta <= 0) continue;

                        Long postId = Long.parseLong(key.substring(KEY_PREFIX.length()));
                        postRepository.incrementViewCountBy(postId, delta);
                        flushed++;
                    } catch (Exception e) {
                        log.warn("조회수 flush 실패: key={}, error={}", key, e.getMessage());
                    }
                }
            }
        }

        if (flushed > 0) {
            log.debug("조회수 flush 완료: {}건", flushed);
        }
    }
}
