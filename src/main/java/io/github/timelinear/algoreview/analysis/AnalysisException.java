package io.github.timelinear.algoreview.analysis;

/**
 * /analyze 처리 중 발생하는, 클라이언트에 에러 코드로 돌려줄 수 있는 예외의 공통 부모.
 */
public class AnalysisException extends RuntimeException {

    private final ErrorCode code;

    public AnalysisException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AnalysisException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** 요청 자체가 잘못됨(400). Bean Validation이 잡지 못하는 규칙(코드 길이 등)에 쓴다. */
    public static class RequestValidation extends AnalysisException {
        public RequestValidation(String message) {
            super(ErrorCode.VALIDATION_ERROR, message);
        }
    }

    /** 하루 LLM 호출 한도 초과(429). */
    public static class DailyLimit extends AnalysisException {
        public DailyLimit(String message) {
            super(ErrorCode.DAILY_LIMIT, message);
        }
    }

    /** LLM 통신 실패의 공통 부모. 재시도까지 끝낸 뒤 던지므로 실제 HTTP 시도 횟수를 들고 있다. */
    public abstract static class Upstream extends AnalysisException {
        private int attempts = 1;

        protected Upstream(ErrorCode code, String message, Throwable cause) {
            super(code, message, cause);
        }

        public int attempts() {
            return attempts;
        }

        public Upstream withAttempts(int attempts) {
            this.attempts = attempts;
            return this;
        }
    }

    /** LLM 서버가 제한 시간 안에 응답하지 않음(502). */
    public static class UpstreamTimeout extends Upstream {
        public UpstreamTimeout(String message, Throwable cause) {
            super(ErrorCode.UPSTREAM_TIMEOUT, message, cause);
        }
    }

    /** LLM 서버가 오류 상태를 돌려주거나 연결할 수 없음(502). */
    public static class UpstreamError extends Upstream {
        private final int httpStatus;        // 0이면 HTTP 응답 없음(연결 오류)
        private final Long retryAfterSeconds; // retry-after 헤더, 없으면 null

        public UpstreamError(String message, int httpStatus, Long retryAfterSeconds, Throwable cause) {
            super(ErrorCode.UPSTREAM_ERROR, message, cause);
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public Long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** LLM 응답을 받았지만 쓸 수 없는 내용(502). 재요청 후에도 실패했을 때 던진다. */
    public static class InvalidLlmOutput extends AnalysisException {
        public InvalidLlmOutput(String message) {
            super(ErrorCode.INVALID_LLM_OUTPUT, message);
        }
    }
}
