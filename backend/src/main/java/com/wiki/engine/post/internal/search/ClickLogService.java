package com.wiki.engine.post.internal.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 검색 클릭 로그 수집 서비스 — Kafka produce + DB 저장.
 *
 * <p>구현:
 * - 클릭 이벤트: Kafka topic "search.clicks"에 produce + DB 즉시 저장
 * - Dwell time: Beacon API로 비동기 수신 → 기존 click_log 레코드에 업데이트
 * - Kafka는 향후 실시간 분석/재학습 파이프라인 연결점
 * - DB는 배치 학습 데이터 추출용 (daily aggregation)
 *
 * <p>클릭 → relevance 변환 (Kim et al., WSDM 2014):
 * - dwell > 120초 → grade 3 (Highly Relevant, SAT click)
 * - dwell 30~120초 → grade 2 (Relevant)
 * - dwell 10~30초 → grade 1 (Marginally Relevant)
 * - dwell < 10초 → grade 0 (misclick)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickLogService {

    private final ClickLogRepository clickLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    private static final String CLICK_TOPIC = "search.clicks";

    /**
     * 검색 결과 클릭을 기록한다.
     * Kafka topic에 produce + DB에 즉시 저장.
     */
    public void recordClick(String query, Long postId, short clickPosition,
                            String sessionId, Long userId) {
        // 1. DB 저장
        var clickLog = new ClickLog(query, postId, clickPosition, sessionId, userId);
        clickLogRepository.save(clickLog);

        // 2. Kafka produce (비동기, 실패해도 DB에는 이미 저장됨)
        try {
            String message = jsonMapper.writeValueAsString(Map.of(
                    "query", query,
                    "postId", postId,
                    "clickPosition", clickPosition,
                    "sessionId", sessionId != null ? sessionId : "",
                    "userId", userId != null ? userId : 0,
                    "timestamp", System.currentTimeMillis()
            ));
            kafkaTemplate.send(CLICK_TOPIC, String.valueOf(postId), message);
        } catch (Exception e) {
            log.warn("클릭 로그 Kafka 전송 실패 (DB 저장은 완료): query='{}', postId={}", query, postId);
        }
    }

    /**
     * Dwell time을 업데이트한다.
     * 프론트엔드에서 Beacon API로 페이지 이탈 시 전송.
     */
    @Transactional
    public void updateDwellTime(String sessionId, Long postId, long dwellTimeMs) {
        clickLogRepository.findTopBySessionIdAndPostIdOrderByCreatedAtDesc(sessionId, postId)
                .ifPresent(clickLog -> clickLog.updateDwellTime(dwellTimeMs));
    }

    /**
     * 30일 이상 된 클릭 로그를 정리한다.
     * 매일 새벽 4시 30분 실행 (SearchLogCollector 정리는 4시).
     */
    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void cleanup() {
        int deleted = clickLogRepository.deleteOlderThan(
                LocalDateTime.now().minusDays(30));
        if (deleted > 0) {
            log.info("클릭 로그 정리: {}건 삭제 (30일 이전)", deleted);
        }
    }
}
