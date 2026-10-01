package io.github.timelinear.algoreview.cache;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * analysis_cache 테이블 전용 저장소. 저장은 ON CONFLICT DO NOTHING이라
 * 같은 코드의 동시 요청이 둘 다 LLM을 호출해도 둘째 저장이 예외 없이 무시된다.
 */
@Repository
public class AnalysisCacheRepository {

    private static final String FIND = """
            SELECT analysis_json::text, model, prompt_version FROM analysis_cache WHERE code_hash = ?
            """;

    private static final String INSERT = """
            INSERT INTO analysis_cache (code_hash, analysis_json, model, prompt_version)
            VALUES (?, ?::jsonb, ?, ?)
            ON CONFLICT (code_hash) DO NOTHING
            """;

    public record Cached(String analysisJson, String model, String promptVersion) {
    }

    private final JdbcTemplate jdbc;

    public AnalysisCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Cached> find(String codeHash) {
        return jdbc.query(FIND,
                (rs, i) -> new Cached(rs.getString(1), rs.getString(2), rs.getString(3)),
                codeHash).stream().findFirst();
    }

    /** @return 새로 저장됐으면 true, 이미 있어서 무시됐으면 false */
    public boolean save(String codeHash, String analysisJson, String model, String promptVersion) {
        return jdbc.update(INSERT, codeHash, analysisJson, model, promptVersion) == 1;
    }
}
