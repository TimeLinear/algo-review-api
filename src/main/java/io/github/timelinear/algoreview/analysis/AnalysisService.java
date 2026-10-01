package io.github.timelinear.algoreview.analysis;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.timelinear.algoreview.api.AnalyzeRequest;
import io.github.timelinear.algoreview.config.LimitsProperties;
import io.github.timelinear.algoreview.config.LlmProperties;
import io.github.timelinear.algoreview.log.AnalysisLogEntry;
import io.github.timelinear.algoreview.log.AnalysisLogEntry.Outcome;
import io.github.timelinear.algoreview.log.AnalysisLogRepository;

/**
 * /analyze의 핵심 흐름: 요청 한도 검사 -> LLM 호출 -> 출력 검증(실패 시 1회 재요청) -> 로그 기록.
 * 통신 수준 재시도는 {@link ReviewGenerator} 구현체 안에 있고, 여기서는 "내용이 쓸 만한가"만 다룬다.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final ReviewGenerator generator;
    private final AnalysisValidator validator;
    private final AnalysisLogRepository logRepository;
    private final LimitsProperties limits;
    private final LlmProperties llm;
    private final Clock clock;

    public AnalysisService(ReviewGenerator generator, AnalysisValidator validator,
            AnalysisLogRepository logRepository, LimitsProperties limits, LlmProperties llm, Clock clock) {
        this.generator = generator;
        this.validator = validator;
        this.logRepository = logRepository;
        this.limits = limits;
        this.llm = llm;
        this.clock = clock;
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

        // 호출마다 누적되는 값. 실패해도 쓴 토큰은 기록한다.
        int attempts = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        String model = null;

        try {
            String lastProblem = null;
            int maxCalls = 1 + llm.outputValidationRetries();
            for (int call = 1; call <= maxCalls; call++) {
                attempts++;
                ReviewResult result = generator.generate(input);
                inputTokens += result.inputTokens();
                outputTokens += result.outputTokens();
                model = result.model();

                AnalysisValidator.Outcome outcome = validator.validate(result);
                if (outcome.valid()) {
                    record(requestedAt, input, codeHash, Outcome.SUCCESS, null, attempts, startNanos,
                            inputTokens, outputTokens, model);
                    return outcome.analysis();
                }
                lastProblem = outcome.problem();
                log.warn("LLM output rejected (call {}/{}): {}", call, maxCalls, lastProblem);
            }
            throw new AnalysisException.InvalidLlmOutput("LLM output failed validation: " + lastProblem);
        } catch (AnalysisException e) {
            record(requestedAt, input, codeHash, Outcome.FAILED, e.code().name(), attempts, startNanos,
                    inputTokens, outputTokens, model);
            throw e;
        }
    }

    private void record(Instant requestedAt, ReviewInput input, String codeHash, Outcome outcome,
            String errorCode, int attempts, long startNanos, int inputTokens, int outputTokens, String model) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        boolean called = attempts > 0;
        AnalysisLogEntry entry = new AnalysisLogEntry(
                requestedAt, input.platform(), input.problemId(), input.language(), codeHash,
                outcome, errorCode, attempts, latencyMs,
                called ? inputTokens : null, called ? outputTokens : null,
                model, called ? llm.promptVersion() : null);
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
