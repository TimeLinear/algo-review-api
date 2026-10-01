package io.github.timelinear.algoreview.analysis;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 리뷰 결과 7필드. 필드명은 원작자 서버 응답(analysis)과 글자 그대로 같다.
 * 확장의 notion_client.js, readme_builder.js가 이 이름을 그대로 쓴다.
 */
public record Analysis(
        @JsonProperty("approach") String approach,
        @JsonProperty("time_complexity") String timeComplexity,
        @JsonProperty("space_complexity") String spaceComplexity,
        @JsonProperty("improvement") String improvement,
        @JsonProperty("next_problem") String nextProblem,
        @JsonProperty("better_code") String betterCode,
        @JsonProperty("tags") List<String> tags) {

    /** JSON 필드명 목록. 스키마 생성과 서버 검증이 같은 목록을 쓴다. */
    public static final List<String> STRING_FIELDS = List.of(
            "approach", "time_complexity", "space_complexity", "improvement", "next_problem", "better_code");
    public static final String TAGS_FIELD = "tags";
    public static final int TAGS_MIN = 1;
    public static final int TAGS_MAX = 4;
}
