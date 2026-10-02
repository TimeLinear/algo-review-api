package io.github.timelinear.algoreview.analysis;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.timelinear.algoreview.api.AnalyzeRequest;
import io.github.timelinear.algoreview.cache.AnalysisCacheRepository;
import io.github.timelinear.algoreview.config.LimitsProperties;
import io.github.timelinear.algoreview.config.LlmProperties;
import io.github.timelinear.algoreview.log.AnalysisLogEntry;
import io.github.timelinear.algoreview.log.AnalysisLogEntry.Outcome;
import io.github.timelinear.algoreview.log.AnalysisLogRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * /analyze의 핵심 흐름:
 * 요청 한도 검사 -> 캐시 조회(적중이면 즉시 반환) -> 하루 호출 한도 -> LLM 호출 -> 출력 검증(실패 시 1회 재요청)
 * -> 캐시 저장 -> 로그 기록.
 * 통신 수준 재시도는 {@link ReviewGenerator} 구현체 안에 있고, 여기서는 "내용이 쓸 만한가"만 다룬다.
 * stop_reason=refusal은 재요청 없이 즉시 실패한다(error_code=LLM_REFUSAL).
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final ReviewGenerator generator;
    private final AnalysisValidator validator;
    private final AnalysisLogRepository logRepository;
    private final AnalysisCacheRepository cacheRepository;
    private final LimitsProperties limits;
    private final LlmProperties llm;
    private final Clock clock;
    private final ObjectMapper mapper;

    public AnalysisService(ReviewGenerator generator, AnalysisValidator validator,
            AnalysisLogRepository logRepository, AnalysisCacheRepository cacheRepository,
            LimitsProperties limits, LlmProperties llm, Clock clock, ObjectMapper mapper) {
        this.generator = generator;
        this.validator = validator;
        this.logRepository = logRepository;
        this.cacheRepository = cacheRepository;
        this.limits = limits;
        this.llm = llm;
        this.clock = clock;
        this.mapper = mapper;
    }

    public Analysis analyze(AnalyzeRequest request) {
        String code = request.submissionInfo().code();
        if (code.length() > limits.codeMaxLength()) {
            throw new AnalysisException.RequestValidation(
                    "submission_info.code exceeds " + limits.codeMaxLength() + " characters");
        }

        ReviewInput input = ReviewInput.from(request);
        String codeHash = CodeHash.of(input.platform(), input.problemId(), input.language(), code);
        Instant requestedAt = clock.instant();
        long startNanos = System.nanoTime();

        // 1. 캐시
        Optional<AnalysisCacheRepository.Cached> cached = cacheRepository.find(codeHash);
        if (cached.isPresent()) {
            Analysis analysis = parseCached(cached.get());
            if (analysis != null) {
                record(requestedAt, input, codeHash, Outcome.CACHE_HIT, null, 0, startNanos,
                        null, null, cached.get().model(), cached.get().promptVersion());
                return analysis;
            }
            log.warn("cached analysis_json unreadable, falling through to LLM: code_hash={}", codeHash);
        }

        // 2. 하루 LLM 호출 한도 (analysis_log 집계. 캐시 적중은 세지 않는다)
        Instant dayStart = LocalDate.now(clock.withZone(limits.dailyResetZone()))
                .atStartOfDay(limits.dailyResetZone()).toInstant();
        long todayCalls = logRepository.countLlmCallsSince(dayStart);
        if (todayCalls >= limits.dailyLlmCalls()) {
            record(requestedAt, input, codeHash, Outcome.REJECTED_LIMIT, ErrorCode.DAILY_LIMIT.name(), 0,
                    startNanos, null, null, null, null);
            throw new AnalysisException.DailyLimit(
                    "daily LLM call limit reached (" + todayCalls + "/" + limits.dailyLlmCalls() + ")");
        }

        // 3. LLM 호출 + 출력 검증. 호출마다 누적되는 값은 실패해도 기록한다.
        int attempts = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        String model = null;
        try {
            String lastProblem = null;
            int maxCalls = 1 + llm.outputValidationRetries();
            for (int call = 1; call <= maxCalls; call++) {
                ReviewResult result;
                try {
                    result = generator.generate(input);
                } catch (AnalysisException.Upstream e) {
                    attempts += e.attempts();
                    throw e;
                }
                attempts += result.attempts();
                inputTokens += result.inputTokens();
                outputTokens += result.outputTokens();
                model = result.model();

                if (result.isRefusal()) {
                    // 거절은 재요청해도 반복될 가능성이 높고 일부 범주는 과금되므로 즉시 실패(D-37).
                    log.warn("LLM refused the request (call {}/{}), not retrying", call, maxCalls);
                    throw new AnalysisException.InvalidLlmOutput("LLM refused the request (stop_reason=refusal)",
                            AnalysisException.InvalidLlmOutput.LOG_CODE_REFUSAL);
                }

                AnalysisValidator.Outcome outcome = validator.validate(result);
                if (outcome.valid()) {
                    saveToCache(codeHash, outcome.analysis(), model);
                    record(requestedAt, input, codeHash, Outcome.SUCCESS, null, attempts, startNanos,
                            inputTokens, outputTokens, model, llm.promptVersion());
                    return outcome.analysis();
                }
                lastProblem = outcome.problem();
                log.warn("LLM output rejected (call {}/{}): {}", call, maxCalls, lastProblem);
            }
            throw new AnalysisException.InvalidLlmOutput("LLM output failed validation: " + lastProblem);
        } catch (AnalysisException e) {
            String logCode = e instanceof AnalysisException.InvalidLlmOutput io ? io.logCode() : e.code().name();
            record(requestedAt, input, codeHash, Outcome.FAILED, logCode, attempts, startNanos,
                    inputTokens, outputTokens, model, llm.promptVersion());
            throw e;
        }
    }

    private Analysis parseCached(AnalysisCacheRepository.Cached cached) {
        try {
            return mapper.readValue(cached.analysisJson(), Analysis.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private void saveToCache(String codeHash, Analysis analysis, String model) {
        try {
            cacheRepository.save(codeHash, mapper.writeValueAsString(analysis), model, llm.promptVersion());
        } catch (RuntimeException e) {
            // 캐시 저장 실패는 응답을 막지 않는다.
            log.error("analysis_cache save failed: code_hash={}", codeHash, e);
        }
    }

    private void record(Instant requestedAt, ReviewInput input, String codeHash, Outcome outcome,
            String errorCode, int attempts, long startNanos, Integer inputTokens, Integer outputTokens,
            String model, String promptVersion) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        AnalysisLogEntry entry = new AnalysisLogEntry(
                requestedAt, input.platform(), input.problemId(), input.language(), codeHash,
                outcome, errorCode, attempts, latencyMs, inputTokens, outputTokens, model, promptVersion);
        try {
            logRepository.insert(entry);
        } catch (RuntimeException e) {
            // 로그 적재 실패가 응답을 막지 않도록 한다. 코드 본문은 어디에도 남기지 않는다.
            log.error("analysis_log insert failed: platform={} problem_id={} outcome={}",
                    input.platform().value(), input.problemId(), outcome, e);
        }
        log.info("analyze platform={} problem_id={} outcome={} error={} attempts={} latency_ms={} tokens={}/{}",
                input.platform().value(), input.problemId(), outcome, errorCode, attempts, latencyMs,
                inputTokens, outputTokens);
    }
}
