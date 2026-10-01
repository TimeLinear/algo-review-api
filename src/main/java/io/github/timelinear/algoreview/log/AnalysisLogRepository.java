package io.github.timelinear.algoreview.log;

import java.sql.Timestamp;

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
}
