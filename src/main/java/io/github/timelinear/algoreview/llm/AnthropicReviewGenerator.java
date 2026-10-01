package io.github.timelinear.algoreview.llm;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 단계 1: 1회 호출, 타임아웃 분류, stop_reason/usage 추출. HTTP 재시도는 단계 2에서 붙인다.
 */
@Component
public class AnthropicReviewGenerator implements ReviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicReviewGenerator.class);
    static final String MESSAGES_PATH = "/v1/messages";
    static final String API_VERSION = "2023-06-01";

    private final RestClient client;
    private final LlmProperties props;
    private final PromptBuilder prompts;
    private final ObjectMapper mapper;

    public AnthropicReviewGenerator(RestClient anthropicRestClient, LlmProperties props,
            PromptBuilder prompts, ObjectMapper mapper) {
        this.client = anthropicRestClient;
        this.props = props;
        this.prompts = prompts;
        this.mapper = mapper;
    }

    @Override
    public ReviewResult generate(ReviewInput input) {
        Map<String, Object> body = requestBody(input);
        return callOnce(body);
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
    static AnalysisException classify(ResourceAccessException e) {
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
