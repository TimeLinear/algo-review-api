package io.github.timelinear.algoreview.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class AnalysisValidatorTest {

    private final AnalysisValidator validator = new AnalysisValidator(JsonMapper.builder().build());

    static final String VALID = """
            {"approach":"정렬 후 투 포인터","time_complexity":"O(N log N) - 정렬","space_complexity":"O(N) - 배열",
             "improvement":"입력을 BufferedReader로","next_problem":"백준 2470 두 용액","better_code":"...",
             "tags":["two-pointers","sorting"]}
            """;

    private static ReviewResult result(String text, String stopReason) {
        return new ReviewResult(text, stopReason, "claude-sonnet-5-5", 100, 50);
    }

    // 실제로 발생하는 입력: 구조화 출력이 정상 종료된 응답.
    @Test
    void acceptsCompleteValidJson() {
        AnalysisValidator.Outcome out = validator.validate(result(VALID, "end_turn"));
        assertThat(out.valid()).isTrue();
        assertThat(out.analysis().tags()).containsExactly("two-pointers", "sorting");
        assertThat(out.analysis().timeComplexity()).startsWith("O(N log N)");
    }

    // 실제로 발생하는 입력: better_code가 길어 max_tokens(4096)에서 잘린 응답. JSON이 완전해 보여도 거부해야 한다.
    @Test
    void rejectsWhenStopReasonIsNotEndTurn() {
        AnalysisValidator.Outcome out = validator.validate(result(VALID, "max_tokens"));
        assertThat(out.valid()).isFalse();
        assertThat(out.problem()).contains("max_tokens");
    }

    // 합성 입력: 스키마가 required를 보장하므로 필드 누락은 드물지만, 스키마 없이 호출된 경우를 대비한다.
    @Test
    void rejectsMissingField() {
        String missing = VALID.replace("\"next_problem\":\"백준 2470 두 용액\",", "");
        AnalysisValidator.Outcome out = validator.validate(result(missing, "end_turn"));
        assertThat(out.valid()).isFalse();
        assertThat(out.problem()).contains("next_problem missing");
    }

    // 실제로 발생하는 입력: 스키마는 string만 보장하므로 모델이 빈 문자열을 넣을 수 있다.
    @Test
    void rejectsBlankString() {
        String blank = VALID.replace("\"improvement\":\"입력을 BufferedReader로\"", "\"improvement\":\"   \"");
        AnalysisValidator.Outcome out = validator.validate(result(blank, "end_turn"));
        assertThat(out.valid()).isFalse();
        assertThat(out.problem()).contains("improvement is blank");
    }

    // 실제로 발생하는 입력: 모델이 태그를 과하게 붙이는 경우(5개 이상)와 비어 있는 경우.
    @Test
    void rejectsTagCountOutOfRange() {
        String five = VALID.replace("[\"two-pointers\",\"sorting\"]", "[\"a\",\"b\",\"c\",\"d\",\"e\"]");
        assertThat(validator.validate(result(five, "end_turn")).problem()).contains("tags count 5");
        String zero = VALID.replace("[\"two-pointers\",\"sorting\"]", "[]");
        assertThat(validator.validate(result(zero, "end_turn")).problem()).contains("tags count 0");
    }

    // 합성 입력: 텍스트 블록이 없거나 JSON이 아닌 경우(예: refusal 뒤 빈 content).
    @Test
    void rejectsNoTextOrNonJson() {
        assertThat(validator.validate(result(null, "end_turn")).problem()).contains("no text block");
        assertThat(validator.validate(result("not json", "end_turn")).problem()).contains("not valid JSON");
    }
}
