package io.github.timelinear.algoreview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이 서버를 호출하는 클라이언트(크롬 확장) 인증 설정. 값은 환경변수 CLIENT_API_KEY에서만 온다.
 */
@ConfigurationProperties(prefix = "client")
public record ClientProperties(String apiKey) {
}
