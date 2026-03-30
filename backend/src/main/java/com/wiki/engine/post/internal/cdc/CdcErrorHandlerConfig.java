package com.wiki.engine.post.internal.cdc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * CDC Consumer 에러 핸들링 — DLQ(Dead Letter Topic) + 재시도.
 *
 * <p>DefaultErrorHandler: 재시도 소진 시 실패 메시지를 {원래토픽}.DLT로 publish.
 * DLT에 원본 토픽, 파티션, 오프셋, 예외 정보가 헤더로 첨부되어 사후 분석/재처리 가능.
 *
 * <p>재시도 정책: 1초 간격 × 9회 = 총 10번 시도.
 * CDC 이벤트는 DB 일시 장애, Lucene I/O 오류 등 일시적 실패가 대부분이므로
 * 재시도로 해결되는 경우가 많다. 10회 후에도 실패하면 DLT로 격리.
 *
 * <p>현업 근거: Confluent 공식 블로그, Uber CDC 파이프라인 — DLQ는 "운영 관찰성 시그널".
 * DLT에 메시지가 쌓이면 알림 → 원인 분석 → 수정 후 재처리.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class CdcErrorHandlerConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 9));
    }
}
