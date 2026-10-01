# algo-review-api

알고리즘 풀이 코드를 받아 AI 코드 리뷰를 구조화된 JSON으로 돌려주는 Spring Boot 서버입니다.
크롬 확장 AlgoNotion이 쓰던 외부 분석 서버를 대체하기 위해 만들었습니다.

> 단계 1(동기 `/analyze`) 상태의 README입니다. 전체 구성은 단계 3에서 정리합니다.

## 실행 (로컬)

```bash
docker compose up -d
```

`.env`에 `ANTHROPIC_API_KEY`, `CLIENT_API_KEY`를 넣은 뒤:

```bash
./gradlew bootRun
```

## API

### POST /analyze

```bash
curl -s http://localhost:8080/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "platform": "jungol",
    "meta_info": {"title": "두 수의 합", "problem_id": "1000", "link": null, "level": null, "language": "Java"},
    "submission_info": {"code": "import java.util.*;\npublic class Main { public static void main(String[] a) { Scanner s = new Scanner(System.in); System.out.println(s.nextInt() + s.nextInt()); } }", "memory": 14000, "time": 120}
  }'
```

성공 응답:

```json
{"ok": true, "analysis": {"approach": "...", "time_complexity": "...", "space_complexity": "...",
  "improvement": "...", "next_problem": "...", "better_code": "...", "tags": ["implementation"]}}
```

실패 응답: `{"ok": false, "error": {"code": "...", "message": "..."}}`

| code | HTTP | 뜻 |
|---|---|---|
| VALIDATION_ERROR | 400 | 필수 필드 누락, platform 값 오류, 코드 길이 초과 |
| UNAUTHORIZED | 401 | X-Api-Key 불일치 (단계 2) |
| DAILY_LIMIT | 429 | 하루 LLM 호출 한도 초과 (단계 2) |
| UPSTREAM_TIMEOUT | 502 | Anthropic API 응답 시간 초과 |
| UPSTREAM_ERROR | 502 | Anthropic API 오류 응답 또는 연결 실패 |
| INVALID_LLM_OUTPUT | 502 | 응답을 받았지만 7필드 검증에 실패(1회 재요청 후) |

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

## 설계 결정

[docs/decisions.md](docs/decisions.md)
