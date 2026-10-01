package io.github.timelinear.algoreview.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.timelinear.algoreview.config.LimitsProperties;
import io.github.timelinear.algoreview.log.AnalysisLogEntry;
import io.github.timelinear.algoreview.log.AnalysisLogRepository;
import java.time.Clock;

/**
 * GET /stats (X-Api-Key 필요). analysis_log 집계만 돌려주고 비용은 계산하지 않는다(단가는 README 안내).
 */
@RestController
public class StatsController {

    private final AnalysisLogRepository logRepository;
    private final LimitsProperties limits;
    private final Clock clock;

    public StatsController(AnalysisLogRepository logRepository, LimitsProperties limits, Clock clock) {
        this.logRepository = logRepository;
        this.limits = limits;
        this.clock = clock;
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        Map<AnalysisLogEntry.Outcome, Long> byOutcome = logRepository.countByOutcome();
        long total = byOutcome.values().stream().mapToLong(Long::longValue).sum();
        long cacheHits = byOutcome.get(AnalysisLogEntry.Outcome.CACHE_HIT);
        double hitRate = total == 0 ? 0.0 : (double) cacheHits / total;

        Instant dayStart = LocalDate.now(clock.withZone(limits.dailyResetZone()))
                .atStartOfDay(limits.dailyResetZone()).toInstant();
        long todayCalls = logRepository.countLlmCallsSince(dayStart);

        AnalysisLogRepository.TokenTotals tokens = logRepository.tokenTotals();
        AnalysisLogRepository.LatencyStats latency = logRepository.successLatency();

        Map<String, Long> outcomes = new LinkedHashMap<>();
        byOutcome.forEach((k, v) -> outcomes.put(k.name(), v));

        return new StatsResponse(total, outcomes, round4(hitRate), todayCalls, limits.dailyLlmCalls(),
                tokens.inputTokens(), tokens.outputTokens(),
                latency.avgMs() == null ? null : Math.round(latency.avgMs()),
                latency.p95Ms() == null ? null : Math.round(latency.p95Ms()));
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
