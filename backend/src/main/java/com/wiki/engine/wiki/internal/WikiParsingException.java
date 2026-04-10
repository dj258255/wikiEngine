package com.wiki.engine.wiki.internal;

/**
 * 위키 데이터(XML, JSON) 파싱 중 발생하는 예외.
 * 배치 임포트 전용 — HTTP 경로에서는 사용하지 않는다.
 */
public class WikiParsingException extends RuntimeException {

    public WikiParsingException(String message) {
        super(message);
    }

    public WikiParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
