package io.github.timelinear.algoreview.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 요청 한도 설정. 값은 application.yml의 {@code limits.*}이며 README "운영 한도" 표와 같아야 한다.
 */
@ConfigurationProperties(prefix = "limits")
public record LimitsProperties(
        int codeMaxLength,
        int dailyLlmCalls,
        ZoneId dailyResetZone) {
}
