package io.github.timelinear.algoreview.analysis;

import org.springframework.http.HttpStatus;

/**
 * 실패 응답의 error.code 값과 HTTP 상태.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS),
    UPSTREAM_TIMEOUT(HttpStatus.BAD_GATEWAY),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),
    INVALID_LLM_OUTPUT(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
