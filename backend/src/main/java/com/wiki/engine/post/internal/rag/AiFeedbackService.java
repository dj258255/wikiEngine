package com.wiki.engine.post.internal.rag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 요약 피드백 저장 + Micrometer 메트릭 노출 서비스.
 *
 * 피드백 데이터 활용:
 * 1. 프롬프트 개선 — thumbs_down 많은 쿼리 패턴 분석
 * 2. 품질 모니터링 — Grafana 대시보드에서 thumbs_up_rate 추이 추적
 * 3. A/B 테스트 — prompt_version별 피드백 비교
 */
@Slf4j
@Service
public class AiFeedbackService {

    private final AiSummaryFeedbackRepository feedbackRepository;
    private final Counter feedbackUpCounter;
    private final Counter feedbackDownCounter;

    public AiFeedbackService(AiSummaryFeedbackRepository feedbackRepository,
                             MeterRegistry meterRegistry) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackUpCounter = Counter.builder("ai_summary_feedback_total")
                .tag("rating", "up")
                .description("AI 요약 긍정 피드백 수")
                .register(meterRegistry);
        this.feedbackDownCounter = Counter.builder("ai_summary_feedback_total")
                .tag("rating", "down")
                .description("AI 요약 부정 피드백 수")
                .register(meterRegistry);
    }

    /**
     * 피드백을 DB에 저장하고 Prometheus 카운터를 증가시킨다.
     */
    public void saveFeedback(AiFeedbackRequest request, Long userId) {
        AiSummaryFeedback feedback = new AiSummaryFeedback(
                request.query(),
                userId,
                request.rating(),
                request.category(),
                request.comment()
        );
        feedbackRepository.save(feedback);

        if (request.rating() > 0) {
            feedbackUpCounter.increment();
        } else {
            feedbackDownCounter.increment();
        }

        log.debug("AI 피드백 저장: query='{}', rating={}, category={}",
                request.query(), request.rating(), request.category());
    }
}
