package io.github.timelinear.algoreview.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.timelinear.algoreview.api.AnalyzeRequest;
import io.github.timelinear.algoreview.api.Platform;
import io.github.timelinear.algoreview.cache.AnalysisCacheRepository;
import io.github.timelinear.algoreview.config.LimitsProperties;
import io.github.timelinear.algoreview.config.LlmProperties;
import io.github.timelinear.algoreview.log.AnalysisLogEntry;
import io.github.timelinear.algoreview.log.AnalysisLogRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가짜 ReviewGenerator로 서비스 흐름만 검증한다: 캐시 적중/미적중, 검증 실패 재요청, 한도 초과, 로그 기록.
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
    static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z"); // KST 2026-10-02 12:00

    FakeGenerator generator;
    AnalysisLogRepository logRepository;
    AnalysisCacheRepository cacheRepository;
    AnalysisService service;

    @BeforeEach
    void setUp() {
        generator = new FakeGenerator();
        logRepository = mock(AnalysisLogRepository.class);
        cacheRepository = mock(AnalysisCacheRepository.class);
        when(cacheRepository.find(anyString())).thenReturn(Optional.empty());
        LimitsProperties limits = new LimitsProperties(50_000, 200, ZoneId.of("Asia/Seoul"));
        LlmProperties llm = new LlmProperties("http://unused", "", "claude-sonnet-5-5", "v1", 4096,
                Duration.ofSeconds(5), Duration.ofSeconds(90),
                new LlmProperties.Retry(2, List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                        Duration.ofSeconds(10), List.of(429, 500, 502, 503, 504, 529)),
                1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AnalysisService(generator, new AnalysisValidator(JsonMapper.builder().build()),
                logRepository, cacheRepository, limits, llm, clock, JsonMapper.builder().build());
    }

    static AnalyzeRequest request(String code) {
        return new AnalyzeRequest(Platform.JUNGOL,
                new AnalyzeRequest.MetaInfo("두 수의 합", "1000", "https://jungol.co.kr/problem/1000", null, "java"),
                new AnalyzeRequest.SubmissionInfo(code, 14000, 120));
    }

    static ReviewResult ok(String text) {
        return new ReviewResult(text, "end_turn", "claude-sonnet-5-5", 300, 200);
    }

    // 실제로 발생하는 입력: 정상 호출 1회로 끝나는 경우. 캐시에 저장되고 SUCCESS로 기록된다.
    @Test
    void cacheMissCallsLlmSavesCacheAndLogsSuccess() {
        generator.then(ok(VALID_JSON));

        Analysis analysis = service.analyze(request("class Main {}"));

        assertThat(analysis.tags()).contains("two-pointers");
        verify(cacheRepository).save(anyString(), anyString(), eq("claude-sonnet-5-5"), eq("v1"));
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.SUCCESS);
        assertThat(entry.attempts()).isEqualTo(1);
        assertThat(entry.inputTokens()).isEqualTo(300);
        assertThat(entry.outputTokens()).isEqualTo(200);
        assertThat(entry.promptVersion()).isEqualTo("v1");
        assertThat(entry.codeHash()).hasSize(64);
    }

    // 실제로 발생하는 입력: 같은 풀이를 다시 제출(확장이 재업로드)한 경우. LLM 호출 없이 CACHE_HIT.
    @Test
    void cacheHitReturnsWithoutLlmCallAndLogsCacheHit() {
        when(cacheRepository.find(anyString())).thenReturn(Optional.of(
                new AnalysisCacheRepository.Cached(VALID_JSON, "claude-sonnet-5-5", "v0")));

        Analysis analysis = service.analyze(request("class Main {}"));

        assertThat(analysis.approach()).isEqualTo("정렬 후 투 포인터");
        assertThat(generator.calls).isZero();
        verify(logRepository, never()).countLlmCallsSince(any());
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.CACHE_HIT);
        assertThat(entry.attempts()).isZero();
        assertThat(entry.inputTokens()).isNull();
        assertThat(entry.promptVersion()).isEqualTo("v0"); // 캐시된 당시의 버전을 기록
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
        verify(cacheRepository, never()).save(anyString(), anyString(), anyString(), anyString());
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.FAILED);
        assertThat(entry.errorCode()).isEqualTo("INVALID_LLM_OUTPUT");
        assertThat(entry.attempts()).isEqualTo(2);
    }

    // 합성 입력: 생성기가 HTTP 3회 시도 끝에 타임아웃. attempts에는 HTTP 시도 횟수(3)가 기록되고 서비스는 재요청하지 않는다.
    @Test
    void upstreamTimeoutIsLoggedWithHttpAttemptsAndRethrown() {
        generator.then(new AnalysisException.UpstreamTimeout("timed out", null).withAttempts(3));

        assertThatThrownBy(() -> service.analyze(request("class Main {}")))
                .isInstanceOf(AnalysisException.UpstreamTimeout.class);

        assertThat(generator.calls).isEqualTo(1);
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.FAILED);
        assertThat(entry.errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(entry.attempts()).isEqualTo(3);
    }

    // 합성 입력: 오늘 KST 기준 LLM 호출이 이미 200회. 거절은 REJECTED_LIMIT로 기록되고 LLM은 호출되지 않는다.
    @Test
    void rejectsWhenDailyLimitReached() {
        Instant kstMidnight = Instant.parse("2026-10-01T15:00:00Z"); // 2026-10-02 00:00 KST
        when(logRepository.countLlmCallsSince(kstMidnight)).thenReturn(200L);

        assertThatThrownBy(() -> service.analyze(request("class Main {}")))
                .isInstanceOf(AnalysisException.DailyLimit.class)
                .hasMessageContaining("200/200");

        assertThat(generator.calls).isZero();
        AnalysisLogEntry entry = captureLog();
        assertThat(entry.outcome()).isEqualTo(AnalysisLogEntry.Outcome.REJECTED_LIMIT);
        assertThat(entry.errorCode()).isEqualTo("DAILY_LIMIT");
        assertThat(entry.attempts()).isZero();
    }

    // 합성 입력: 199회면 아직 허용된다(경계).
    @Test
    void allowsWhenOneBelowDailyLimit() {
        when(logRepository.countLlmCallsSince(any())).thenReturn(199L);
        generator.then(ok(VALID_JSON));

        service.analyze(request("class Main {}"));

        assertThat(generator.calls).isEqualTo(1);
    }

    // 합성 입력: 50,001자 코드. 실제 제출 코드가 이 길이를 넘는 일은 드물지만 한도 경계를 확인한다.
    @Test
    void rejectsCodeOverMaxLengthWithoutCallingLlmOrLogging() {
        String tooLong = "x".repeat(50_001);

        assertThatThrownBy(() -> service.analyze(request(tooLong)))
                .isInstanceOf(AnalysisException.RequestValidation.class)
                .hasMessageContaining("50000");

        assertThat(generator.calls).isZero();
        verify(logRepository, never()).insert(any());
    }

    private AnalysisLogEntry captureLog() {
        ArgumentCaptor<AnalysisLogEntry> captor = ArgumentCaptor.forClass(AnalysisLogEntry.class);
        verify(logRepository).insert(captor.capture());
        return captor.getValue();
    }
}
