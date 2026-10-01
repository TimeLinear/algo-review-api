package io.github.timelinear.algoreview.log;

import java.time.Instant;

import io.github.timelinear.algoreview.api.Platform;

/**
 * analysis_log 한 행. 제출 코드 본문은 넣지 않는다(codeHash만).
 */
public record AnalysisLogEntry(
        Instant requestedAt,
        Platform platform,
        String problemId,
        String language,
        String codeHash,
        Outcome outcome,
        String errorCode,
        int attempts,
        long latencyMs,
        Integer inputTokens,
        Integer outputTokens,
        String model,
        String promptVersion) {

    public enum Outcome {
        CACHE_HIT, SUCCESS, FAILED, REJECTED_LIMIT
    }
}
