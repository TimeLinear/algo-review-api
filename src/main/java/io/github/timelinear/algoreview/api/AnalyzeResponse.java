package io.github.timelinear.algoreview.api;

import io.github.timelinear.algoreview.analysis.Analysis;

/**
 * POST /analyze 성공 응답. 원작자 서버 WebhookResponse와 같은 구조({@code ok}, {@code analysis}).
 * 필드를 추가하지 않는다(확장 호환).
 */
public record AnalyzeResponse(boolean ok, Analysis analysis) {

    public static AnalyzeResponse of(Analysis analysis) {
        return new AnalyzeResponse(true, analysis);
    }
}
