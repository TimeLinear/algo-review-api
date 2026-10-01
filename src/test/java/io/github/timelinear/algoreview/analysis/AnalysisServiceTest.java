package io.github.timelinear.algoreview.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.timelinear.algoreview.api.AnalyzeRequest;
import io.github.timelinear.algoreview.api.Platform;
import io.github.timelinear.algoreview.config.LimitsProperties;
import io.github.timelinear.algoreview.config.LlmProperties;
import io.github.timelinear.algoreview.log.AnalysisLogEntry;
import io.github.timelinear.algoreview.log.AnalysisLogRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가짜 ReviewGenerator로 서비스 흐름만 검증한다. 단계 1 범위: 검증 실패 재요청, 코드 길이 한도, 로그 기록.
 */
class AnalysisServiceTest {

    /** 호출마다 미리 넣어 둔 결과(또는 예외)를 순서대로 돌려주는 가짜 생성기. */
    static class FakeGenerator implements ReviewGenerator {
        final Deque<Object> script = new ArrayDeque<>();
        int calls = 0;

        FakeGenerator then(Object resultOrException) {
            script.add(resultOrException);
            return this;
        }

        @Override
        public ReviewResult generate(ReviewInput input) {
            calls++;
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (ReviewResult) next;
        }
    }

    static final String VALID_JSON = AnalysisValidatorTest.VALID;

    FakeGenerator generator;
    AnalysisLogRepository logRepository;
    AnalysisService service;

    @BeforeEach
    void setUp() {
        generator = new FakeGenerator();
        logRepository = mock(AnalysisLogRepository.class);
        LimitsProperties limits = new LimitsProperties(50_000, 200, ZoneId.of("Asia/Seoul"));
        LlmProperties llm = new LlmProperties("http://unused", "", "claude-sonnet-5-5", "v1", 4096,
                Duration.ofSeconds(5), Duration.ofSeconds(90),
                new LlmProperties.Retry(2, List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                        Duration.ofSeconds(10), List.of(429, 500, 502, 503, 504, 529)),
                1);
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ZoneOffset.UTC);
        service = new AnalysisService(generator, new AnalysisValidator(JsonMapper.builder().build()),
                logRepository, limits, llm, clock);
    }

    static AnalyzeRequest request(String code) {
        return new AnalyzeRequest(Platform.JUNGOL,
                new AnalyzeRequest.MetaInfo("두 수의 합", "1000", null, "Bronze", "Java"),
                new AnalyzeRequest.SubmissionInfo(code, 14000, 120));
    }

    static ReviewResult ok(String text) {
        return new ReviewResult(text, "end_turn", "claude-sonnet-5-5", 300, 200);
    }

    // 실제로 발생하는 입력: 정상 호출 1회로 끝나는 경우.
    @Test
    void successOnFirstCallLogsSuccessWithOneAttempt() {
        generator.then(ok(VALID_JSON));

        Analysis analysis = service.analyze(request("class Main {}"));

        assertThat(analysis.tags()).contains("two-pointers");
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.SUCCESS);
        assertThat(entry.attempts()).isEqualTo(1);
        assertThat(entry.inputTokens()).isEqualTo(300);
        assertThat(entry.outputTokens()).isEqualTo(200);
        assertThat(entry.promptVersion()).isEqualTo("v1");
        assertThat(entry.codeHash()).hasSize(64);
    }

    // 실제로 발생하는 입력: 첫 응답이 max_tokens로 잘리고 두 번째가 정상인 경우. 토큰은 두 호출의 합을 기록한다.
    @Test
    void retriesOnceWhenOutputInvalidThenSucceeds() {
        generator.then(new ReviewResult(VALID_JSON, "max_tokens", "claude-sonnet-5-5", 300, 4096))
                .then(ok(VALID_JSON));

        service.analyze(request("class Main {}"));

        assertThat(generator.calls).isEqualTo(2);
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.SUCCESS);
        assertThat(entry.attempts()).isEqualTo(2);
        assertThat(entry.inputTokens()).isEqualTo(600);
        assertThat(entry.outputTokens()).isEqualTo(4296);
    }

    // 실제로 발생하는 입력: 두 번 모두 검증 실패(예: 태그 0개). 재요청은 1회뿐이므로 총 2회 호출 후 INVALID_LLM_OUTPUT.
    @Test
    void failsWithInvalidLlmOutputAfterSecondInvalid() {
        String noTags = VALID_JSON.replace("[\"two-pointers\",\"sorting\"]", "[]");
        generator.then(ok(noTags)).then(ok(noTags));

        assertThatThrownBy(() -> service.analyze(request("class Main {}")))
                .isInstanceOf(AnalysisException.InvalidLlmOutput.class)
                .hasMessageContaining("tags count 0");

        assertThat(generator.calls).isEqualTo(2);
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.FAILED);
        assertThat(entry.errorCode()).isEqualTo("INVALID_LLM_OUTPUT");
        assertThat(entry.attempts()).isEqualTo(2);
    }

    // 합성 입력: 통신 오류는 생성기 안에서 이미 재시도를 끝낸 뒤 올라오므로 서비스는 재요청하지 않는다.
    @Test
    void upstreamTimeoutIsLoggedAndRethrownWithoutExtraCall() {
        generator.then(new AnalysisException.UpstreamTimeout("timed out", null));

        assertThatThrownBy(() -> service.analyze(request("class Main {}")))
                .isInstanceOf(AnalysisException.UpstreamTimeout.class);

        assertThat(generator.calls).isEqualTo(1);
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.FAILED);
        assertThat(entry.errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(entry.attempts()).isEqualTo(1);
    }

    // 합성 입력: 50,001자 코드. 실제 제출 코드가 이 길이를 넘는 일은 드물지만 한도 경계를 확인한다.
    @Test
    void rejectsCodeOverMaxLengthWithoutCallingLlmOrLogging() {
        String tooLong = "x".repeat(50_001);

        assertThatThrownBy(() -> service.analyze(request(tooLong)))
                .isInstanceOf(AnalysisException.RequestValidation.class)
                .hasMessageContaining("50000");

        assertThat(generator.calls).isZero();
        verify(logRepository, org.mockito.Mockito.never()).insert(any());
    }

    private AnalysisLogEntry captureLog() {
        ArgumentCaptor<AnalysisLogEntry> captor = ArgumentCaptor.forClass(AnalysisLogEntry.class);
        verify(logRepository).insert(captor.capture());
        return captor.getValue();
    }
}
