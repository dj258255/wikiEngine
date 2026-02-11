package com.wiki.engine.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 통일된 API 응답 래퍼.
 * 모든 API 응답을 일관된 형식으로 제공한다.
 *
 * @param status HTTP 상태 코드
 * @param data 응답 데이터 (성공 시)
 * @param message 에러 메시지 (실패 시)
 * @param code 에러 코드 (실패 시, ErrorCode enum name)
 * @param errors 필드별 검증 에러 (validation 실패 시)
 * @param timestamp 응답 시각
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int status,
        T data,
        String message,
        String code,
        Map<String, String> errors,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, data, null, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, data, null, null, null, Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(
                errorCode.getStatus().value(),
                null,
                errorCode.getMessage(),
                errorCode.name(),
                null,
                Instant.now()
        );
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(
                errorCode.getStatus().value(),
                null,
                message,
                errorCode.name(),
                null,
                Instant.now()
        );
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, Map<String, String> errors) {
        return new ApiResponse<>(
                errorCode.getStatus().value(),
                null,
                errorCode.getMessage(),
                errorCode.name(),
                errors,
                Instant.now()
        );
    }
}
