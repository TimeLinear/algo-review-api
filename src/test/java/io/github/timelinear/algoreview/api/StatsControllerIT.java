package io.github.timelinear.algoreview.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.timelinear.algoreview.TestcontainersConfiguration;
import io.github.timelinear.algoreview.log.AnalysisLogEntry;
import io.github.timelinear.algoreview.log.AnalysisLogRepository;

/**
 * GET /stats를 실제 Postgres 집계(percentile_cont 포함)로 검증한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "client.api-key=test-client-key")
class StatsControllerIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    AnalysisLogRepository logs;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM analysis_log");
        // 합성 입력: 오늘 SUCCESS 3건(지연 1000/2000/3000ms), CACHE_HIT 1건, FAILED 1건, REJECTED_LIMIT 1건.
        Instant now = Instant.now();
        for (int latency : new int[] {1000, 2000, 3000}) {
            logs.insert(entry(now, AnalysisLogEntry.Outcome.SUCCESS, null, 1, latency, 100, 50));
        }
        logs.insert(entry(now, AnalysisLogEntry.Outcome.CACHE_HIT, null, 0, 5, null, null));
        logs.insert(entry(now, AnalysisLogEntry.Outcome.FAILED, "UPSTREAM_TIMEOUT", 3, 270_000, null, null));
        logs.insert(entry(now, AnalysisLogEntry.Outcome.REJECTED_LIMIT, "DAILY_LIMIT", 0, 3, null, null));
        // 어제(KST 자정 이전) SUCCESS 1건: 전체 집계에는 들어가지만 오늘 호출 수에는 들어가지 않는다.
        logs.insert(entry(now.minusSeconds(2 * 86_400), AnalysisLogEntry.Outcome.SUCCESS, null, 1, 500, 10, 5));
    }

    static AnalysisLogEntry entry(Instant at, AnalysisLogEntry.Outcome outcome, String error, int attempts,
            long latency, Integer in, Integer out) {
        return new AnalysisLogEntry(at, Platform.BAEKJOON, "1000", "java", "f".repeat(64), outcome, error,
                attempts, latency, in, out, in == null ? null : "claude-sonnet-5-5", "v1");
    }

    @Test
    void statsAggregatesLog() throws Exception {
        mvc.perform(get("/stats").header("X-Api-Key", "test-client-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_requests").value(7))
                .andExpect(jsonPath("$.by_outcome.SUCCESS").value(4))
                .andExpect(jsonPath("$.by_outcome.CACHE_HIT").value(1))
                .andExpect(jsonPath("$.by_outcome.FAILED").value(1))
                .andExpect(jsonPath("$.by_outcome.REJECTED_LIMIT").value(1))
                .andExpect(jsonPath("$.cache_hit_rate").value(0.1429))
                .andExpect(jsonPath("$.today_llm_calls").value(4)) // 오늘 SUCCESS 3 + FAILED 1
                .andExpect(jsonPath("$.daily_llm_call_limit").value(200))
                .andExpect(jsonPath("$.input_tokens_total").value(310))
                .andExpect(jsonPath("$.output_tokens_total").value(155))
                .andExpect(jsonPath("$.success_latency_avg_ms").value(1625)) // (1000+2000+3000+500)/4
                .andExpect(jsonPath("$.success_latency_p95_ms").value(2850)); // percentile_cont 0.95
    }

    // 합성 입력: 키 없이 /stats 호출은 401.
    @Test
    void statsRequiresApiKey() throws Exception {
        mvc.perform(get("/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // 실제로 발생하는 입력: Render 헬스 체크는 키 없이 /actuator/health를 호출한다.
    @Test
    void healthDoesNotRequireApiKey() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
