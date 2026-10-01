package io.github.timelinear.algoreview.api;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.timelinear.algoreview.analysis.AnalysisException;
import io.github.timelinear.algoreview.analysis.ErrorCode;

/**
 * 모든 실패를 {@code { ok: false, error: { code, message } }} 형식으로 바꾼다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 필수 필드 누락, 공백 등 Bean Validation 실패. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> jsonPath(fe) + " " + fe.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(ErrorCode.VALIDATION_ERROR, message);
    }

    /** JSON 자체가 깨졌거나 platform 값이 enum에 없을 때. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadable(HttpMessageNotReadableException e) {
        String message = rootMessage(e);
        return error(ErrorCode.VALIDATION_ERROR, "request body is not valid: " + message);
    }

    @ExceptionHandler(AnalysisException.class)
    public ResponseEntity<ErrorResponse> onAnalysis(AnalysisException e) {
        if (e.code() == ErrorCode.VALIDATION_ERROR) {
            return error(e.code(), e.getMessage());
        }
        log.warn("analyze failed: code={} message={}", e.code(), e.getMessage());
        return error(e.code(), e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message));
    }

    /** camelCase 필드명(metaInfo.problemId)을 요청 JSON 이름(meta_info.problem_id)으로 바꿔 보여 준다. */
    private static String jsonPath(FieldError fe) {
        return fe.getField().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null) {
            return "malformed JSON";
        }
        // Jackson 메시지는 길고 내부 경로를 포함하므로 첫 줄만 쓴다.
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }
}
