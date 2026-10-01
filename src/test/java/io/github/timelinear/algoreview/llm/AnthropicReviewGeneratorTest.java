package io.github.timelinear.algoreview.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import io.github.timelinear.algoreview.analysis.AnalysisException;
import io.github.timelinear.algoreview.analysis.ReviewInput;
import io.github.timelinear.algoreview.analysis.ReviewResult;
import io.github.timelinear.algoreview.api.Platform;
import io.github.timelinear.algoreview.config.LlmProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * MockWebServer로 Anthropic API를 흉내 내어 재시도·타임아웃·상태 코드 분류를 HTTP 수준에서 검증한다.
 * 대기는 가짜 Sleeper로 기록만 하고 실제로 잠들지 않는다.
 */
class AnthropicReviewGeneratorTest {

    static final String SUCCESS_BODY = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
             "content":[{"type":"text","text":"{\\"approach\\":\\"a\\"}"}],
             "stop_reason":"end_turn","usage":{"input_tokens":1478,"output_tokens":664}}
            """;

    MockWebServer server;
    ObjectMapper mapper = JsonMapper.builder().build();
    List<Duration> sleeps = new ArrayList<>();
    AnthropicReviewGenerator generator;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        generator = generator(Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    AnthropicReviewGenerator generator(Duration readTimeout) {
        LlmProperties props = new LlmProperties(server.url("/").toString().replaceAll("/$", ""),
                "test-anthropic-key", "claude-sonnet-5-5", "v1", 4096,
                Duration.ofSeconds(1), readTimeout,
                new LlmProperties.Retry(2, List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                        Duration.ofSeconds(10), List.of(429, 500, 502, 503, 504, 529)),
                1);
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(props.connectTimeout()).withReadTimeout(props.readTimeout());
        RestClient client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
        return new AnthropicReviewGenerator(client, props, new PromptBuilder(), mapper, sleeps::add);
    }

    static ReviewInput input() {
        return new ReviewInput(Platform.BAEKJOON, "A+B", "1000", null, "Bronze V", "java",
                "class Main {}", 14000, 120);
    }

    static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    static MockResponse error(int status, String type) {
        return json(status, "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"m\"}}");
    }

    // 실제로 발생하는 입력: 정상 응답. 요청 본문·헤더가 계약대로 나가는지도 함께 본다.
    @Test
    void successParsesTextStopReasonUsageAndSendsContractHeaders() throws Exception {
        server.enqueue(json(200, SUCCESS_BODY));

        ReviewResult result = generator.generate(input());

        assertThat(result.text()).isEqualTo("{\"approach\":\"a\"}");
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(result.inputTokens()).isEqualTo(1478);
        assertThat(result.outputTokens()).isEqualTo(664);
        assertThat(result.attempts()).isEqualTo(1);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/messages");
        assertThat(req.getHeader("x-api-key")).isEqualTo("test-anthropic-key");
        assertThat(req.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(req.getHeader("anthropic-beta")).isNull();
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertThat(body.path("model").asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("required").size()).isEqualTo(7);
        assertThat(body.path("messages").size()).isEqualTo(1); // prefill(assistant 턴) 없음
        assertThat(body.path("messages").get(0).path("content").asString()).contains("<submission>");
    }

    // 실제로 발생하는 입력: 속도 제한 429 뒤 성공. retry-after 헤더 값(3초)만큼 대기해야 한다.
    @Test
    void retriesAfter429UsingRetryAfterHeader() {
        server.enqueue(error(429, "rate_limit_error").setHeader("retry-after", "3"));
        server.enqueue(json(200, SUCCESS_BODY));

        ReviewResult result = generator.generate(input());

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    // 실제로 발생하는 입력: 과부하 529 뒤 성공. retry-after가 없으면 설정의 첫 대기(1초).
    @Test
    void retriesAfter529WithDefaultBackoff() {
        server.enqueue(error(529, "overloaded_error"));
        server.enqueue(json(200, SUCCESS_BODY));

        ReviewResult result = generator.generate(input());

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    // 합성 입력: retry-after가 상한(10초)보다 큰 값(60초). 상한으로 잘라야 한다.
    @Test
    void retryAfterIsCappedAtMax() {
        server.enqueue(error(429, "rate_limit_error").setHeader("retry-after", "60"));
        server.enqueue(json(200, SUCCESS_BODY));

        generator.generate(input());

        assertThat(sleeps).containsExactly(Duration.ofSeconds(10));
    }

    // 합성 입력: 응답 타임아웃 3회 연속(읽기 타임아웃을 300ms로 줄여 재현). 대기는 1초, 2초, 그 뒤 UPSTREAM_TIMEOUT.
    @Test
    void threeTimeoutsBecomeUpstreamTimeoutWithThreeAttempts() {
        generator = generator(Duration.ofMillis(300));
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        }

        assertThatThrownBy(() -> generator.generate(input()))
                .isInstanceOf(AnalysisException.UpstreamTimeout.class)
                .satisfies(e -> assertThat(((AnalysisException.Upstream) e).attempts()).isEqualTo(3));

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    // 실제로 발생하는 입력: 잘못된 API 키(401). 재시도 없이 즉시 UPSTREAM_ERROR.
    @Test
    void unauthorizedIsNotRetried() {
        server.enqueue(error(401, "authentication_error"));

        assertThatThrownBy(() -> generator.generate(input()))
                .isInstanceOf(AnalysisException.UpstreamError.class)
                .hasMessageContaining("401")
                .hasMessageContaining("authentication_error");

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    // 합성 입력: 400(잘못된 요청)도 재시도하지 않는다.
    @Test
    void badRequestIsNotRetried() {
        server.enqueue(error(400, "invalid_request_error"));

        assertThatThrownBy(() -> generator.generate(input()))
                .isInstanceOf(AnalysisException.UpstreamError.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // 합성 입력: 500이 3회 연속. 재시도 소진 후 UPSTREAM_ERROR, attempts=3.
    @Test
    void serverErrorsExhaustRetries() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(error(500, "api_error"));
        }

        assertThatThrownBy(() -> generator.generate(input()))
                .isInstanceOf(AnalysisException.UpstreamError.class)
                .satisfies(e -> assertThat(((AnalysisException.Upstream) e).attempts()).isEqualTo(3));
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    // 실제로 발생하는 입력: better_code가 길어 max_tokens에서 잘린 응답. HTTP는 200이지만 isComplete()가 false여야 한다.
    @Test
    void maxTokensStopReasonIsReportedAsIncomplete() {
        server.enqueue(json(200, SUCCESS_BODY.replace("\"end_turn\"", "\"max_tokens\"")));

        ReviewResult result = generator.generate(input());

        assertThat(result.isComplete()).isFalse();
        assertThat(result.stopReason()).isEqualTo("max_tokens");
        assertThat(server.getRequestCount()).isEqualTo(1); // HTTP 수준에서는 재시도하지 않는다(내용 재요청은 서비스 몫)
    }

    // 합성 입력: 연결 거부(서버 종료). 연결 오류도 재시도 대상이며 소진 후 UPSTREAM_ERROR.
    @Test
    void connectionRefusedIsRetriedThenUpstreamError() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> generator.generate(input()))
                .isInstanceOf(AnalysisException.UpstreamError.class)
                .hasMessageContaining("unreachable");
        assertThat(sleeps).hasSize(2);
    }

}
