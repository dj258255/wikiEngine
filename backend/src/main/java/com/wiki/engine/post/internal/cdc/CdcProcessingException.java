package com.wiki.engine.post.internal.cdc;

/**
 * CDC(Debezium) 메시지 처리 중 발생하는 예외.
 * Spring Kafka DefaultErrorHandler가 이 예외를 보고 재시도 여부를 판단한다.
 */
public class CdcProcessingException extends RuntimeException {

    public CdcProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
