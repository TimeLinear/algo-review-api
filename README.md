# algo-review-api

알고리즘 풀이 코드를 받아 AI 코드 리뷰를 구조화된 JSON으로 돌려주는 Spring Boot 서버입니다.
크롬 확장 AlgoNotion이 쓰던 외부 분석 서버를 대체하기 위해 만들었습니다.

> 단계 2(운영 품질) 상태의 README입니다. 전체 구성은 단계 3에서 정리합니다.

## 실행 (로컬)

```bash
docker compose up -d
```

`.env`에 `ANTHROPIC_API_KEY`, `CLIENT_API_KEY`를 넣은 뒤:

```bash
./gradlew bootRun
```

## API

모든 요청(`/actuator/health` 제외)은 `X-Api-Key` 헤더가 환경변수 `CLIENT_API_KEY`와 같아야 합니다.

### POST /analyze

요청 본문은 확장 `buildJungolWebhookPayload`의 출력 형태와 같습니다(language는 소문자, level은 문자열 또는 null, memory는 KB, time은 ms).

```bash
curl -s http://localhost:8080/analyze \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $CLIENT_API_KEY" \
  -d '{
    "platform": "jungol",
    "meta_info": {"title": "두 수의 합", "problem_id": "1000", "link": "https://jungol.co.kr/problem/1000", "level": null, "language": "java"},
    "submission_info": {"code": "import java.util.*;\npublic class Main { public static void main(String[] a) { Scanner s = new Scanner(System.in); System.out.println(s.nextInt() + s.nextInt()); } }", "memory": 14000, "time": 120}
  }'
```

성공 응답:

```json
{"ok": true, "analysis": {"approach": "...", "time_complexity": "...", "space_complexity": "...",
  "improvement": "...", "next_problem": "...", "better_code": "...", "tags": ["implementation"]}}
```

같은 제출(platform + problem_id + language + 정규화한 code)을 다시 보내면 LLM 호출 없이 캐시에서 돌려줍니다.

### GET /stats

```bash
curl -s http://localhost:8080/stats -H "X-Api-Key: $CLIENT_API_KEY"
```

```json
{"total_requests": 12, "by_outcome": {"CACHE_HIT": 3, "SUCCESS": 8, "FAILED": 1, "REJECTED_LIMIT": 0},
 "cache_hit_rate": 0.25, "today_llm_calls": 9, "daily_llm_call_limit": 200,
 "input_tokens_total": 13302, "output_tokens_total": 5976,
 "success_latency_avg_ms": 8420, "success_latency_p95_ms": 11890}
```

비용은 계산하지 않습니다. 기본 모델 claude-sonnet-5-5의 단가는 입력 $2 / 출력 $10 (1M 토큰당, 2026-10 기준)이며, 토큰 합계에 곱하면 됩니다.

### 에러 응답

`{"ok": false, "error": {"code": "...", "message": "..."}}`

| code | HTTP | 뜻 |
|---|---|---|
| VALIDATION_ERROR | 400 | 필수 필드 누락, platform 값 오류, 코드 길이 초과 |
| UNAUTHORIZED | 401 | X-Api-Key 없음 또는 불일치 |
| DAILY_LIMIT | 429 | 하루 LLM 호출 한도 초과 |
| UPSTREAM_TIMEOUT | 502 | Anthropic API 응답 시간 초과(재시도 소진) |
| UPSTREAM_ERROR | 502 | Anthropic API 오류 응답 또는 연결 실패(재시도 소진) |
| INVALID_LLM_OUTPUT | 502 | 응답을 받았지만 7필드 검증에 실패(1회 재요청 후) |
| INTERNAL_ERROR | 500 | 예상 밖 오류. 메시지는 고정 문구이며 상세는 서버 로그에만 남김 |

## 운영 한도

`src/main/resources/application.yml`과 같은 값이며 `ConfigDocConsistencyTest`가 일치를 검사합니다.

| 항목 | 값 |
|---|---|
| 코드 최대 길이 | 50,000자 (초과 시 400) |
| LLM 연결 타임아웃 | 5초 |
| LLM 응답 타임아웃 | 90초 |
| LLM max_tokens | 4096 |
| 재시도 | 최초 1회 + 최대 2회. 대상: HTTP 429, 500, 502, 503, 504, 529, 타임아웃, 연결 오류. 400/401/403은 재시도하지 않음 |
| 재시도 대기 | 1초, 2초. retry-after 헤더가 있으면 그 값(최대 10초) |
| 출력 검증 실패 재시도 | 1회 |
| 하루 LLM 호출 한도 | 200회, Asia/Seoul 자정 초기화. 캐시 적중은 세지 않음 |

## 테스트

```bash
./gradlew test
```

| 종류 | 무엇을 검증하는가 |
|---|---|
| 단위(서비스) | 가짜 ReviewGenerator로 캐시 적중/미적중, 출력 검증 실패 재요청, 하루 한도, 로그 기록 |
| HTTP(MockWebServer) | Anthropic API를 흉내 내어 429/529 뒤 성공, retry-after 상한, 타임아웃 3회, 401/400 무재시도, max_tokens |
| 컨트롤러(MockMvc) | 필수 누락 400, 키 없음/불일치 401, jungol 허용, 에러 응답 형식, 500 고정 문구 |
| 통합(Testcontainers, Postgres 17) | Flyway, analysis_log/analysis_cache 저장소, ON CONFLICT, /stats 집계(p95). Docker 없으면 건너뜀 |

## 설계 결정

[docs/decisions.md](docs/decisions.md)
