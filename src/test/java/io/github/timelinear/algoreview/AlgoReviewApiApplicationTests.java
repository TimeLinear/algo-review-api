package io.github.timelinear.algoreview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 컨텍스트 기동 + Flyway V1 적용 확인. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AlgoReviewApiApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void contextLoadsAndMigrationApplied() {
		// 합성 입력: 실제 요청 없이 마이그레이션 결과만 확인한다.
		Integer count = jdbc.queryForObject("SELECT count(*) FROM analysis_log", Integer.class);
		assertThat(count).isZero();
	}

}
