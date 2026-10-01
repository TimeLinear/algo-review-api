package io.github.timelinear.algoreview.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /stats 응답. 필드명은 /analyze와 같은 snake_case.
 *
 * @param cacheHitRate            CACHE_HIT / 전체 요청 수 (0~1). 요청이 없으면 0
 * @param successLatencyAvgMs     SUCCESS만의 평균 지연(ms). 없으면 null
 * @param successLatencyP95Ms     SUCCESS만의 p95 지연(ms, percentile_cont). 없으면 null
 */
public record StatsResponse(
        @JsonProperty("total_requests") long totalRequests,
        @JsonProperty("by_outcome") Map<String, Long> byOutcome,
        @JsonProperty("cache_hit_rate") double cacheHitRate,
        @JsonProperty("today_llm_calls") long todayLlmCalls,
        @JsonProperty("daily_llm_call_limit") int dailyLlmCallLimit,
        @JsonProperty("input_tokens_total") long inputTokensTotal,
        @JsonProperty("output_tokens_total") long outputTokensTotal,
        @JsonProperty("success_latency_avg_ms") Long successLatencyAvgMs,
        @JsonProperty("success_latency_p95_ms") Long successLatencyP95Ms) {
}
