package io.github.timelinear.algoreview.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic API 호출 설정. 값은 application.yml의 {@code llm.*}.
 * 운영 한도(타임아웃, max_tokens, 재시도)는 README "운영 한도" 표와 같아야 한다(ConfigDocConsistencyTest).
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        String promptVersion,
        int maxTokens,
        Duration connectTimeout,
        Duration readTimeout,
        Retry retry,
        int outputValidationRetries) {

    public record Retry(
            int maxRetries,
            List<Duration> backoffs,
            Duration maxRetryAfter,
            List<Integer> retryableStatuses) {
    }
}
