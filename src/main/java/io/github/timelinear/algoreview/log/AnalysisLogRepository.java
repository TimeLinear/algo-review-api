package io.github.timelinear.algoreview.log;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * analysis_log 테이블 전용 저장소. SQL은 이 클래스 안에 문자열로 둔다(docs/decisions.md D-05).
 */
@Repository
public class AnalysisLogRepository {

    private static final String INSERT = """
            INSERT INTO analysis_log
              (requested_at, platform, problem_id, language, code_hash, outcome, error_code,
               attempts, latency_ms, input_tokens, output_tokens, model, prompt_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** LLM을 실제로 호출한 요청 수. 캐시 적중과 한도 거절은 호출이 없으므로 세지 않는다. */
    private static final String COUNT_LLM_CALLS_SINCE = """
            SELECT count(*) FROM analysis_log
            WHERE requested_at >= ? AND outcome IN ('SUCCESS', 'FAILED')
            """;

    private static final String COUNT_BY_OUTCOME = """
            SELECT outcome, count(*) FROM analysis_log GROUP BY outcome
            """;

    private static final String TOKEN_TOTALS = """
            SELECT coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0) FROM analysis_log
            """;

    /** SUCCESS만의 지연시간 통계. p95는 percentile_cont(선형 보간). */
    private static final String SUCCESS_LATENCY = """
            SELECT avg(latency_ms), percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)
            FROM analysis_log WHERE outcome = 'SUCCESS'
            """;

    public record LatencyStats(Double avgMs, Double p95Ms) {
    }

    public record TokenTotals(long inputTokens, long outputTokens) {
    }

    private final JdbcTemplate jdbc;

    public AnalysisLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AnalysisLogEntry e) {
        jdbc.update(INSERT,
                Timestamp.from(e.requestedAt()),
                e.platform().value(),
                e.problemId(),
                e.language(),
                e.codeHash(),
                e.outcome().name(),
                e.errorCode(),
                e.attempts(),
                e.latencyMs(),
                e.inputTokens(),
                e.outputTokens(),
                e.model(),
                e.promptVersion());
    }

    public long countLlmCallsSince(Instant since) {
        Long n = jdbc.queryForObject(COUNT_LLM_CALLS_SINCE, Long.class, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    public Map<AnalysisLogEntry.Outcome, Long> countByOutcome() {
        Map<AnalysisLogEntry.Outcome, Long> result = new EnumMap<>(AnalysisLogEntry.Outcome.class);
        for (AnalysisLogEntry.Outcome o : AnalysisLogEntry.Outcome.values()) {
            result.put(o, 0L);
        }
        jdbc.query(COUNT_BY_OUTCOME, rs -> {
            result.put(AnalysisLogEntry.Outcome.valueOf(rs.getString(1)), rs.getLong(2));
        });
        return result;
    }

    public TokenTotals tokenTotals() {
        return jdbc.queryForObject(TOKEN_TOTALS, (rs, i) -> new TokenTotals(rs.getLong(1), rs.getLong(2)));
    }

    public LatencyStats successLatency() {
        return jdbc.queryForObject(SUCCESS_LATENCY, (rs, i) -> {
            double avg = rs.getDouble(1);
            Double avgMs = rs.wasNull() ? null : avg;
            double p95 = rs.getDouble(2);
            Double p95Ms = rs.wasNull() ? null : p95;
            return new LatencyStats(avgMs, p95Ms);
        });
    }
}
