package com.wiki.engine.post.internal.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 검색 로그 인메모리 집계 + 주기적 DB flush (시간 버킷 기반).
 *
 * record()는 인메모리 ConcurrentHashMap에 카운트만 증가시킨다 (I/O 없음).
 * 5분마다 flush()가 버퍼를 통째로 교체(volatile swap)하고,
 * 현재 시간 버킷(시간 단위)으로 DB에 upsert한다.
 *
 * 시간 버킷 예시: 14:23에 flush → time_bucket = 14:00:00
 * 같은 시간대의 여러 flush(14:05, 14:10, ...)는 같은 버킷에 누적된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchLogCollector {

    private volatile ConcurrentHashMap<String, LongAdder> buffer = new ConcurrentHashMap<>();
    private final SearchLogRepository searchLogRepository;

    /**
     * 검색 완료 시 호출. 인메모리 집계만 수행하므로 I/O 없음.
     */
    public void record(String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        buffer.computeIfAbsent(query.toLowerCase().trim(), k -> new LongAdder()).increment();
    }

    /**
     * 5분마다 버퍼를 DB에 flush.
     * volatile swap으로 원자적 버퍼 교체 후, 현재 시간 버킷으로 upsert.
     */
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void flush() {
        ConcurrentHashMap<String, LongAdder> snapshot = buffer;
        buffer = new ConcurrentHashMap<>();

        if (snapshot.isEmpty()) {
            return;
        }

        LocalDateTime timeBucket = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

        int count = 0;
        for (var entry : snapshot.entrySet()) {
            searchLogRepository.upsert(entry.getKey(), timeBucket, entry.getValue().sum());
            count++;
        }
        log.info("Search logs flushed: {} unique queries to bucket {}", count, timeBucket);
    }

    /**
     * 30일 이전 로그 삭제. 매일 새벽 4시 실행.
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        int deleted = searchLogRepository.deleteOlderThan(threshold);
        if (deleted > 0) {
            log.info("Search logs cleanup: {} old rows deleted (before {})", deleted, threshold);
        }
    }
}
