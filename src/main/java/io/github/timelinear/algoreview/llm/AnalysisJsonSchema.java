package io.github.timelinear.algoreview.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.timelinear.algoreview.analysis.Analysis;

/**
 * Anthropic 구조화 출력(output_config.format)에 넘기는 JSON 스키마.
 * 7필드 모두 required, additionalProperties false. 필드 목록은 {@link Analysis}와 같은 상수를 쓴다.
 */
public final class AnalysisJsonSchema {

    private AnalysisJsonSchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : Analysis.STRING_FIELDS) {
            properties.put(field, Map.of("type", "string"));
        }
        properties.put(Analysis.TAGS_FIELD, Map.of(
                "type", "array",
                "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        List<String> required = new java.util.ArrayList<>(Analysis.STRING_FIELDS);
        required.add(Analysis.TAGS_FIELD);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 요청 본문의 output_config 값. */
    public static Map<String, Object> outputConfig() {
        return Map.of("format", Map.of(
                "type", "json_schema",
                "schema", schema()));
    }
}
