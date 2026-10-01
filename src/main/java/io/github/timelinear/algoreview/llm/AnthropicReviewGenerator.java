package io.github.timelinear.algoreview.llm;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.timelinear.algoreview.analysis.AnalysisException;
import io.github.timelinear.algoreview.analysis.ReviewGenerator;
import io.github.timelinear.algoreview.analysis.ReviewInput;
import io.github.timelinear.algoreview.analysis.ReviewResult;
import io.github.timelinear.algoreview.config.LlmProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Anthropic Messages API(https://api.anthropic.com/v1/messages)를 RestClient로 직접 호출한다.
 * SDK를 쓰지 않는 이유: 타임아웃, 재시도, 토큰 기록을 코드로 드러내기 위함(docs/decisions.md D-09).
 *
 * 재시도 규칙(운영 한도 표): 최초 1회 + 최대 N회. 대상은 설정의 상태 코드(429/500/502/503/504/529),
 * 타임아웃, 연결 오류. 그 외 4xx(400/401/403 등)는 즉시 실패. 대기는 1초, 2초이되
 * retry-after 헤더가 있으면 그 값(상한 있음)을 쓴다.
 */
@Component
public class AnthropicReviewGenerator implements ReviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicReviewGenerator.class);
    static final String MESSAGES_PATH = "/v1/messages";
    static final String API_VERSION = "2023-06-01";

    /** 재시도 대기. 테스트에서 실제로 잠들지 않도록 바꿔 끼운다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;

        Sleeper REAL = d -> Thread.sleep(d.toMillis());
    }

    private final RestClient client;
    private final LlmProperties props;
    private final PromptBuilder prompts;
    private final ObjectMapper mapper;
    private final Sleeper sleeper;

    @Autowired
    public AnthropicReviewGenerator(RestClient anthropicRestClient, LlmProperties props,
            PromptBuilder prompts, ObjectMapper mapper) {
        this(anthropicRestClient, props, prompts, mapper, Sleeper.REAL);
    }

    AnthropicReviewGenerator(RestClient client, LlmProperties props, PromptBuilder prompts,
            ObjectMapper mapper, Sleeper sleeper) {
        this.client = client;
        this.props = props;
        this.prompts = prompts;
        this.mapper = mapper;
        this.sleeper = sleeper;
    }

    @Override
    public ReviewResult generate(ReviewInput input) {
        Map<String, Object> body = requestBody(input);
        int maxAttempts = 1 + props.retry().maxRetries();
        for (int attempt = 1; ; attempt++) {
            try {
                return callOnce(body).withAttempts(attempt);
            } catch (AnalysisException.Upstream e) {
                if (attempt >= maxAttempts || !isRetryable(e)) {
                    throw e.withAttempts(attempt);
                }
                Duration wait = backoff(attempt, e);
                log.warn("Anthropic call attempt {}/{} failed ({}), retrying in {}", attempt, maxAttempts,
                        e.getMessage(), wait);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e.withAttempts(attempt);
                }
            }
        }
    }

    boolean isRetryable(AnalysisException.Upstream e) {
        if (e instanceof AnalysisException.UpstreamTimeout) {
            return true;
        }
        int status = ((AnalysisException.UpstreamError) e).httpStatus();
        return status == 0 || props.retry().retryableStatuses().contains(status);
    }

    /** attempt번째 실패 뒤의 대기. retry-after가 있으면 그 값(상한 적용), 없으면 설정의 n번째 대기. */
    Duration backoff(int attempt, AnalysisException.Upstream e) {
        LlmProperties.Retry retry = props.retry();
        if (e instanceof AnalysisException.UpstreamError ue && ue.retryAfterSeconds() != null) {
            Duration fromHeader = Duration.ofSeconds(Math.max(0, ue.retryAfterSeconds()));
            return fromHeader.compareTo(retry.maxRetryAfter()) > 0 ? retry.maxRetryAfter() : fromHeader;
        }
        List<Duration> backoffs = retry.backoffs();
        return backoffs.get(Math.min(attempt, backoffs.size()) - 1);
    }

    Map<String, Object> requestBody(ReviewInput input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        body.put("system", prompts.systemPrompt());
        body.put("messages", List.of(Map.of("role", "user", "content", prompts.userMessage(input))));
        body.put("output_config", AnalysisJsonSchema.outputConfig());
        return body;
    }

    /** HTTP 1회. 2xx가 아니면 UpstreamError, 타임아웃이면 UpstreamTimeout. */
    ReviewResult callOnce(Map<String, Object> body) {
        try {
            return client.post()
                    .uri(MESSAGES_PATH)
                    .header("x-api-key", props.apiKey())
                    .header("anthropic-version", API_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status < 200 || status >= 300) {
                            Long retryAfter = parseRetryAfter(response.getHeaders().getFirst("retry-after"));
                            String errorType = extractErrorType(text);
                            log.warn("Anthropic API HTTP {} type={} retry-after={}", status, errorType, retryAfter);
                            throw new AnalysisException.UpstreamError(
                                    "Anthropic API responded HTTP " + status + (errorType != null ? " (" + errorType + ")" : ""),
                                    status, retryAfter, null);
                        }
                        return parseMessage(text);
                    }, false);
        } catch (ResourceAccessException e) {
            throw classify(e);
        }
    }

    ReviewResult parseMessage(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            throw new AnalysisException.UpstreamError("Anthropic API returned non-JSON body", 200, null, e);
        }
        String text = null;
        JsonNode content = root.path("content");
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asString())) {
                text = block.path("text").asString();
                break;
            }
        }
        JsonNode usage = root.path("usage");
        return new ReviewResult(
                text,
                root.path("stop_reason").asString(null),
                root.path("model").asString(null),
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0));
    }

    private String extractErrorType(String body) {
        try {
            return mapper.readTree(body).path("error").path("type").asString(null);
        } catch (JacksonException e) {
            return null;
        }
    }

    static Long parseRetryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null; // HTTP-date 형식은 다루지 않는다
        }
    }

    /** JDK HttpClient 예외를 타임아웃/연결 오류로 나눈다. */
    static AnalysisException.Upstream classify(ResourceAccessException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return new AnalysisException.UpstreamTimeout("Anthropic API timed out: " + t.getMessage(), e);
            }
            t = t.getCause();
        }
        Throwable cause = e.getCause();
        String detail = cause instanceof IOException ? cause.getMessage() : e.getMessage();
        return new AnalysisException.UpstreamError("Anthropic API unreachable: " + detail, 0, null, e);
    }
}
