package com.wiki.engine.common;

import lombok.Getter;

/**
 * 비즈니스 로직 예외.
 * ErrorCode를 기반으로 HTTP 상태 코드와 에러 메시지를 전달한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
