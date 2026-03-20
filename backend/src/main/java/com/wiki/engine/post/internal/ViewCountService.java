package com.wiki.engine.post.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

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
@RequiredArgsConstructor
public class ViewCountService {

    private final StringRedisTemplate redisTemplate;
    private final PostRepository postRepository;

    private static final String KEY_PREFIX = "post:views:";

    /**
     * 조회수 1 증가 (Redis INCR, O(1), ~0.1ms).
     * DB를 타지 않으므로 R/W 라우팅과 무관.
     */
    public void increment(Long postId) {
        try {
            redisTemplate.opsForValue().increment(KEY_PREFIX + postId);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 조회수 INCR 실패 (무시): postId={}", postId);
        }
    }

    /**
     * 누적된 조회수를 DB에 배치 flush (30초 주기).
     * Redis에서 읽고 삭제 → DB UPDATE.
     */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void flushToDB() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        int flushed = 0;
        for (String key : keys) {
            try {
                String value = redisTemplate.opsForValue().getAndDelete(key);
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

        if (flushed > 0) {
            log.debug("조회수 flush 완료: {}건", flushed);
        }
    }
}
