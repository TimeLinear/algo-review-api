package io.github.timelinear.algoreview.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.timelinear.algoreview.TestcontainersConfiguration;

/**
 * 실제 Postgres에서 analysis_cache의 저장·조회와 ON CONFLICT DO NOTHING을 확인한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisCacheRepositoryIT {

    @Autowired
    AnalysisCacheRepository repository;

    // 실제로 발생하는 입력: 첫 성공 후 저장, 같은 코드 재제출 시 조회.
    @Test
    void saveThenFind() {
        String hash = "c".repeat(64);
        String json = "{\"approach\":\"a\",\"tags\":[\"dfs\"]}";

        assertThat(repository.save(hash, json, "claude-sonnet-5-5", "v1")).isTrue();

        Optional<AnalysisCacheRepository.Cached> found = repository.find(hash);
        assertThat(found).isPresent();
        assertThat(found.get().model()).isEqualTo("claude-sonnet-5-5");
        assertThat(found.get().promptVersion()).isEqualTo("v1");
        assertThat(found.get().analysisJson()).contains("\"dfs\"");
    }

    // 실제로 발생하는 입력: 같은 코드의 동시 요청 둘이 모두 LLM을 호출한 뒤 둘 다 저장을 시도. 둘째는 예외 없이 무시된다.
    @Test
    void secondSaveIsIgnoredWithoutException() {
        String hash = "d".repeat(64);
        assertThat(repository.save(hash, "{\"v\":1}", "m", "v1")).isTrue();
        assertThat(repository.save(hash, "{\"v\":2}", "m", "v2")).isFalse();
        assertThat(repository.find(hash).get().analysisJson()).contains("1");
    }

    @Test
    void missReturnsEmpty() {
        assertThat(repository.find("e".repeat(64))).isEmpty();
    }
}
