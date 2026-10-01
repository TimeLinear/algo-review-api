package io.github.timelinear.algoreview.api;

import io.github.timelinear.algoreview.analysis.ErrorCode;

/**
 * 실패 응답 {@code { "ok": false, "error": { "code": "...", "message": "..." } }}.
 */
public record ErrorResponse(boolean ok, Error error) {

    public record Error(String code, String message) {
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(false, new Error(code.name(), message));
    }
}
