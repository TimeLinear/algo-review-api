-- 같은 제출(platform + problem_id + language + 정규화한 code)의 리뷰 결과 캐시.
-- 키에 prompt_version은 넣지 않는다(docs/decisions.md D-21). 코드 본문은 저장하지 않는다.
CREATE TABLE analysis_cache (
    code_hash       CHAR(64)     PRIMARY KEY,
    analysis_json   JSONB        NOT NULL,
    model           VARCHAR(100) NOT NULL,
    prompt_version  VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
