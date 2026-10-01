package io.github.timelinear.algoreview.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * README "운영 한도" 표와 application.yml의 값이 같은지 검사한다.
 * 둘 중 하나만 고치면 이 테스트가 실패한다.
 */
class ConfigDocConsistencyTest {

    static Map<String, String> readme; // 항목 -> 값 셀 원문
    static Map<String, Object> yml;

    @BeforeAll
    static void load() throws IOException {
        String md = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        readme = parseLimitsTable(md);
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            yml = new Yaml().load(in);
        }
    }

    /** "## 운영 한도" 아래 첫 표의 "| 항목 | 값 |" 행들을 읽는다. */
    static Map<String, String> parseLimitsTable(String md) {
        int start = md.indexOf("## 운영 한도");
        assertThat(start).as("README에 '## 운영 한도' 섹션이 있어야 한다").isGreaterThanOrEqualTo(0);
        int next = md.indexOf("\n## ", start + 1);
        String section = next > 0 ? md.substring(start, next) : md.substring(start);
        Map<String, String> rows = new LinkedHashMap<>();
        Pattern row = Pattern.compile("^\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*$", Pattern.MULTILINE);
        Matcher m = row.matcher(section);
        while (m.find()) {
            String key = m.group(1);
            if (key.equals("항목") || key.startsWith("-")) {
                continue;
            }
            rows.put(key, m.group(2));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    static Object yml(String dotted) {
        Object cur = yml;
        for (String part : dotted.split("\\.")) {
            cur = ((Map<String, Object>) cur).get(part);
            assertThat(cur).as("application.yml에 %s 가 있어야 한다", dotted).isNotNull();
        }
        return cur;
    }

    static int firstInt(String cell) {
        Matcher m = Pattern.compile("\\d[\\d,]*").matcher(cell);
        assertThat(m.find()).as("숫자가 있어야 한다: %s", cell).isTrue();
        return Integer.parseInt(m.group().replace(",", ""));
    }

    @Test
    void codeMaxLength() {
        assertThat(yml("limits.code-max-length")).isEqualTo(firstInt(readme.get("코드 최대 길이")));
    }

    @Test
    void llmTimeouts() {
        assertThat(yml("llm.connect-timeout")).isEqualTo(firstInt(readme.get("LLM 연결 타임아웃")) + "s");
        assertThat(yml("llm.read-timeout")).isEqualTo(firstInt(readme.get("LLM 응답 타임아웃")) + "s");
    }

    @Test
    void llmMaxTokens() {
        assertThat(yml("llm.max-tokens")).isEqualTo(firstInt(readme.get("LLM max_tokens")));
    }

    @Test
    void retryPolicy() {
        String cell = readme.get("재시도");
        // "최초 1회 + 최대 2회" -> 두 번째 숫자가 max-retries
        Matcher m = Pattern.compile("최대 (\\d+)회").matcher(cell);
        assertThat(m.find()).isTrue();
        assertThat(yml("llm.retry.max-retries")).isEqualTo(Integer.parseInt(m.group(1)));

        // 대상 상태 코드 목록
        Matcher codes = Pattern.compile("\\b(4\\d\\d|5\\d\\d)\\b").matcher(cell.substring(cell.indexOf("대상:"), cell.indexOf("타임아웃")));
        var expected = new java.util.ArrayList<Integer>();
        while (codes.find()) {
            expected.add(Integer.parseInt(codes.group()));
        }
        var actual = java.util.Arrays.stream(yml("llm.retry.retryable-statuses").toString().split(","))
                .map(String::trim).map(Integer::parseInt).toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void retryBackoff() {
        String cell = readme.get("재시도 대기");
        Matcher secs = Pattern.compile("(\\d+)초").matcher(cell);
        var found = new java.util.ArrayList<String>();
        while (secs.find()) {
            found.add(secs.group(1) + "s");
        }
        // 마지막 숫자는 retry-after 상한
        String maxRetryAfter = found.remove(found.size() - 1);
        assertThat(yml("llm.retry.backoffs")).isEqualTo(String.join(",", found));
        assertThat(yml("llm.retry.max-retry-after")).isEqualTo(maxRetryAfter);
    }

    @Test
    void outputValidationRetries() {
        assertThat(yml("llm.output-validation-retries")).isEqualTo(firstInt(readme.get("출력 검증 실패 재시도")));
    }

    @Test
    void dailyLimit() {
        String cell = readme.get("하루 LLM 호출 한도");
        assertThat(yml("limits.daily-llm-calls")).isEqualTo(firstInt(cell));
        assertThat(cell).contains(yml("limits.daily-reset-zone").toString());
    }
}
