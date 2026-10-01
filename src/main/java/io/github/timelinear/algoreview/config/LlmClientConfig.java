package io.github.timelinear.algoreview.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Anthropic API 호출용 RestClient. JDK HttpClient 기반이며 연결/응답 타임아웃을 운영 한도대로 건다.
 * 상태 코드별 처리는 {@code AnthropicReviewGenerator}가 exchange()로 직접 한다(기본 4xx/5xx 예외를 쓰지 않음).
 */
@Configuration(proxyBeanMethods = false)
public class LlmClientConfig {

    @Bean
    RestClient anthropicRestClient(RestClient.Builder builder, LlmProperties props) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(props.connectTimeout())
                .withReadTimeout(props.readTimeout());
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.jdk().build(settings);
        return builder
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
