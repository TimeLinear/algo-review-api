-- 분석 요청 1건당 1행. 제출 코드 본문은 저장하지 않는다(code_hash만).
CREATE TABLE analysis_log (
    id              BIGSERIAL PRIMARY KEY,
    requested_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    platform        VARCHAR(20)  NOT NULL,
    problem_id      VARCHAR(100) NOT NULL,
    language        VARCHAR(50)  NOT NULL,
    code_hash       CHAR(64)     NOT NULL,
    outcome         VARCHAR(20)  NOT NULL
                    CHECK (outcome IN ('CACHE_HIT', 'SUCCESS', 'FAILED', 'REJECTED_LIMIT')),
    error_code      VARCHAR(40),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    latency_ms      INTEGER      NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    model           VARCHAR(100),
    prompt_version  VARCHAR(20)
);

-- 하루 LLM 호출 수 집계(시간 범위 + outcome)용
CREATE INDEX idx_analysis_log_requested_at_outcome ON analysis_log (requested_at, outcome);
