package io.github.timelinear.algoreview.analysis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM 출력의 서버 측 검증. 스키마 적합(API가 보장)과 별개로
 * (1) stop_reason이 end_turn인지, (2) JSON 파싱, (3) 7필드 존재, (4) 문자열 필드 공백 아님, (5) tags 1~4개를 본다.
 * 실패 사유는 사람이 읽을 문장으로 돌려주어 로그와 에러 메시지에 그대로 쓴다.
 */
@Component
public class AnalysisValidator {

    private final ObjectMapper mapper;

    public AnalysisValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 검증 결과. 둘 중 하나만 채워진다. */
    public record Outcome(Analysis analysis, String problem) {
        public boolean valid() {
            return analysis != null;
        }

        static Outcome ok(Analysis a) {
            return new Outcome(a, null);
        }

        static Outcome fail(String problem) {
            return new Outcome(null, problem);
        }
    }

    public Outcome validate(ReviewResult result) {
        if (!result.isComplete()) {
            return Outcome.fail("stop_reason=" + result.stopReason() + " (expected end_turn)");
        }
        if (result.text() == null || result.text().isBlank()) {
            return Outcome.fail("response has no text block");
        }
        JsonNode root;
        try {
            root = mapper.readTree(result.text());
        } catch (JacksonException e) {
            return Outcome.fail("output is not valid JSON: " + e.getOriginalMessage());
        }
        if (!root.isObject()) {
            return Outcome.fail("output is not a JSON object");
        }

        List<String> problems = new ArrayList<>();
        for (String field : Analysis.STRING_FIELDS) {
            JsonNode node = root.get(field);
            if (node == null || node.isNull()) {
                problems.add(field + " missing");
            } else if (!node.isString()) {
                problems.add(field + " is not a string");
            } else if (node.asString().isBlank()) {
                problems.add(field + " is blank");
            }
        }
        JsonNode tags = root.get(Analysis.TAGS_FIELD);
        if (tags == null || !tags.isArray()) {
            problems.add("tags missing or not an array");
        } else {
            int n = tags.size();
            if (n < Analysis.TAGS_MIN || n > Analysis.TAGS_MAX) {
                problems.add("tags count " + n + " (expected " + Analysis.TAGS_MIN + "~" + Analysis.TAGS_MAX + ")");
            }
            for (JsonNode t : tags) {
                if (!t.isString() || t.asString().isBlank()) {
                    problems.add("tags contains a non-string or blank entry");
                    break;
                }
            }
        }
        if (!problems.isEmpty()) {
            return Outcome.fail(String.join("; ", problems));
        }
        return Outcome.ok(mapper.treeToValue(root, Analysis.class));
    }
}
