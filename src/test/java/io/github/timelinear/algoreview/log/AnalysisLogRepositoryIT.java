package io.github.timelinear.algoreview.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.timelinear.algoreview.TestcontainersConfiguration;
import io.github.timelinear.algoreview.api.Platform;

/**
 * 실제 Postgres(Testcontainers)에 analysis_log 행이 들어가는지 확인한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisLogRepositoryIT {

    @Autowired
    AnalysisLogRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    // 실제로 발생하는 입력: 성공 1회 호출의 로그. 토큰·모델·프롬프트 버전이 모두 채워진다.
    @Test
    void insertsSuccessRow() {
        String hash = "a".repeat(64);
        repository.insert(new AnalysisLogEntry(Instant.parse("2026-10-02T03:00:00Z"), Platform.JUNGOL, "1000",
                "Java", hash, AnalysisLogEntry.Outcome.SUCCESS, null, 1, 8717, 1478, 664,
                "claude-sonnet-5-5", "v1"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM analysis_log WHERE code_hash = ?", hash);
        assertThat(row.get("platform")).isEqualTo("jungol");
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("error_code")).isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("input_tokens")).isEqualTo(1478);
        assertThat(row.get("prompt_version")).isEqualTo("v1");
    }

    // 실제로 발생하는 입력: 타임아웃 실패 로그. 토큰은 알 수 없어 null, 모델도 null.
    @Test
    void insertsFailedRowWithNulls() {
        String hash = "b".repeat(64);
        repository.insert(new AnalysisLogEntry(Instant.now(), Platform.BAEKJOON, "1000", "Python", hash,
                AnalysisLogEntry.Outcome.FAILED, "UPSTREAM_TIMEOUT", 3, 270_000, null, null, null, "v1"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM analysis_log WHERE code_hash = ?", hash);
        assertThat(row.get("error_code")).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(row.get("input_tokens")).isNull();
        assertThat(row.get("model")).isNull();
    }
}
