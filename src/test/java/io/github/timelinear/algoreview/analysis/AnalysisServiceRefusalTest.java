package io.github.timelinear.algoreview.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import io.github.timelinear.algoreview.api.AnalyzeRequest;
import io.github.timelinear.algoreview.api.Platform;
import io.github.timelinear.algoreview.cache.AnalysisCacheRepository;
import io.github.timelinear.algoreview.config.LimitsProperties;
import io.github.timelinear.algoreview.config.LlmProperties;
import io.github.timelinear.algoreview.llm.AnthropicReviewGenerator;
import io.github.timelinear.algoreview.llm.PromptBuilder;
import io.github.timelinear.algoreview.log.AnalysisLogEntry;
import io.github.timelinear.algoreview.log.AnalysisLogRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 거절(stop_reason=refusal) 처리를 HTTP부터 서비스까지 한 번에 본다: MockWebServer가 refusal을 돌려주면
 * 재요청 없이(요청 1회, attempts 1) INVALID_LLM_OUTPUT이 되고 error_code는 LLM_REFUSAL로 기록된다(D-37).
 */
class AnalysisServiceRefusalTest {

    // 실제로 발생하는 입력: 안전 분류기가 거절한 응답. HTTP 200, content 비어 있음, stop_details에 범주.
    static final String REFUSAL_BODY = """
            {"id":"msg_r","type":"message","role":"assistant","model":"claude-sonnet-5-5",
             "content":[],"stop_reason":"refusal",
             "stop_details":{"type":"refusal","category":"cyber","explanation":"..."},
             "usage":{"input_tokens":1500,"output_tokens":0}}
            """;

    MockWebServer server;
    AnalysisLogRepository logRepository;
    AnalysisService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        LlmProperties llm = new LlmProperties(server.url("/").toString().replaceAll("/$", ""), "k",
                "claude-sonnet-5-5", "v1", 4096, Duration.ofSeconds(1), Duration.ofSeconds(5),
                new LlmProperties.Retry(2, List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                        Duration.ofSeconds(10), List.of(429, 500, 502, 503, 504, 529)),
                1);
        RestClient client = RestClient.builder().baseUrl(llm.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(HttpClientSettings.defaults()))
                .build();
        JsonMapper mapper = JsonMapper.builder().build();
        AnthropicReviewGenerator generator = new AnthropicReviewGenerator(client, llm, new PromptBuilder(), mapper);

        logRepository = mock(AnalysisLogRepository.class);
        AnalysisCacheRepository cache = mock(AnalysisCacheRepository.class);
        when(cache.find(anyString())).thenReturn(Optional.empty());
        service = new AnalysisService(generator, new AnalysisValidator(mapper), logRepository, cache,
                new LimitsProperties(50_000, 200, ZoneId.of("Asia/Seoul")), llm,
                Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ZoneOffset.UTC), mapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void refusalFailsImmediatelyWithoutRetryAndIsLoggedAsLlmRefusal() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(REFUSAL_BODY));
        // 재요청이 일어나면 이 두 번째 응답이 소비된다. 소비되지 않아야 한다.
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(REFUSAL_BODY));

        AnalyzeRequest request = new AnalyzeRequest(Platform.BAEKJOON,
                new AnalyzeRequest.MetaInfo("t", "1000", null, null, "java"),
                new AnalyzeRequest.SubmissionInfo("class Main {}", null, null));

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(AnalysisException.InvalidLlmOutput.class)
                .hasMessageContaining("refusal")
                .satisfies(e -> assertThat(((AnalysisException) e).code()).isEqualTo(ErrorCode.INVALID_LLM_OUTPUT));

        assertThat(server.getRequestCount()).isEqualTo(1);
        ArgumentCaptor<AnalysisLogEntry> captor = ArgumentCaptor.forClass(AnalysisLogEntry.class);
        verify(logRepository).insert(captor.capture());
        AnalysisLogEntry entry = captor.getValue();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.FAILED);
        assertThat(entry.errorCode()).isEqualTo("LLM_REFUSAL");
        assertThat(entry.attempts()).isEqualTo(1);
        assertThat(entry.inputTokens()).isEqualTo(1500); // 거절도 입력 토큰은 기록한다
    }
}
