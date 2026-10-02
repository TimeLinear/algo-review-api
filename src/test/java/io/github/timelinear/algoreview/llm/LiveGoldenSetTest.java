package io.github.timelinear.algoreview.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import io.github.timelinear.algoreview.analysis.AnalysisValidator;
import io.github.timelinear.algoreview.analysis.ReviewInput;
import io.github.timelinear.algoreview.analysis.ReviewResult;
import io.github.timelinear.algoreview.api.AnalyzeRequest;
import io.github.timelinear.algoreview.config.LlmProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * 라이브 골든셋: TimeLinear/algorithm-java의 실제 풀이 5개를 진짜 Anthropic API에 보내
 * 7필드 검증 통과 여부, 토큰, 지연시간을 표로 출력한다.
 *
 * 기본 빌드(./gradlew test)에서는 제외되고, ANTHROPIC_API_KEY가 있을 때 ./gradlew liveTest 로만 실행된다.
 * 호출마다 과금되므로 프롬프트를 바꿨을 때만 돌린다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LiveGoldenSetTest {

    static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "claude-sonnet-5-5");

    record Row(String file, String platform, String problemId, boolean valid, String problem,
            int inputTokens, int outputTokens, long latencyMs, int attempts, List<String> tags) {
    }

    @Test
    void goldenSetPassesServerSideValidation() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        LlmProperties props = new LlmProperties("https://api.anthropic.com", System.getenv("ANTHROPIC_API_KEY"),
                MODEL, "v1", 4096, Duration.ofSeconds(5), Duration.ofSeconds(90),
                new LlmProperties.Retry(2, List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                        Duration.ofSeconds(10), List.of(429, 500, 502, 503, 504, 529)),
                1);
        RestClient client = RestClient.builder().baseUrl(props.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(HttpClientSettings.defaults()
                        .withConnectTimeout(props.connectTimeout()).withReadTimeout(props.readTimeout())))
                .build();
        AnthropicReviewGenerator generator = new AnthropicReviewGenerator(client, props, new PromptBuilder(), mapper);
        AnalysisValidator validator = new AnalysisValidator(mapper);

        List<Row> rows = new ArrayList<>();
        try (Stream<Path> files = Files.list(GOLDEN_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                AnalyzeRequest req = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), AnalyzeRequest.class);
                ReviewInput input = ReviewInput.from(req);
                long start = System.nanoTime();
                ReviewResult result = generator.generate(input);
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                AnalysisValidator.Outcome outcome = validator.validate(result);
                rows.add(new Row(file.getFileName().toString(), req.platform().value(), req.metaInfo().problemId(),
                        outcome.valid(), outcome.problem(), result.inputTokens(), result.outputTokens(), latencyMs,
                        result.attempts(), outcome.valid() ? outcome.analysis().tags() : List.of()));
            }
        }

        StringBuilder table = new StringBuilder();
        table.append("\n| 파일 | 플랫폼 | 문제 | 검증 | 입력 토큰 | 출력 토큰 | 지연(ms) | 시도 | tags |\n");
        table.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            table.append(String.format("| %s | %s | %s | %s | %d | %d | %d | %d | %s |%n",
                    r.file(), r.platform(), r.problemId(), r.valid() ? "통과" : "실패: " + r.problem(),
                    r.inputTokens(), r.outputTokens(), r.latencyMs(), r.attempts(), String.join(", ", r.tags())));
        }
        System.out.println("GOLDEN SET RESULT (model=" + MODEL + ")" + table);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/golden-set.md"), table.toString(), StandardCharsets.UTF_8);

        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(r -> assertThat(r.valid()).as(r.file() + ": " + r.problem()).isTrue());
    }
}
