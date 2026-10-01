package io.github.timelinear.algoreview.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /analyze 요청 본문. 필드명은 원작자 서버 WebhookPayload(openapi.json, 2026-10-01)와 글자 그대로 같다.
 * 확장(AlgoNotion-modify)이 이 이름을 그대로 쓰므로 바꾸면 호환이 깨진다.
 */
public record AnalyzeRequest(
        @NotNull Platform platform,
        @NotNull @Valid @JsonProperty("meta_info") MetaInfo metaInfo,
        @NotNull @Valid @JsonProperty("submission_info") SubmissionInfo submissionInfo) {

    public record MetaInfo(
            @NotBlank String title,
            @NotBlank @JsonProperty("problem_id") String problemId,
            String link,
            String level,
            @NotBlank String language) {
    }

    public record SubmissionInfo(
            @NotNull String code,
            Integer memory,
            Integer time) {
    }
}
