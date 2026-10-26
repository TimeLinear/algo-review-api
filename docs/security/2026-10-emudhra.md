# 보안 영향 검토: eMudhra 중간 CA 키 유출과 Node.js 10/21 패치 (2026-10)

2026-10-27 작성. 같은 날 받은 보안 영향 분석 보고서(이하 "원 보고서")를 레포 코드, 실제 배포 바이너리, 원 출처와 대조해 검증하고, 바로 할 수 있는 조치를 적용한 기록이다.

## 결론

| 항목 | 영향 | 상태 |
| --- | --- | --- |
| eMudhra 중간 CA 키 유출 | 있음(현재 배포 이미지 기준) | 조치 적용(A1, A3), 배포 대기 |
| Node.js 10/21 보안 패치 | 없음 | 조치 불필요 |
| Supabase 연결 인증서 미검증(`sslmode=require`) | 있음(기존 문제) | 인증서 파일과 Render 환경변수 변경 필요(A2) |
| 빌드 시 Gradle 배포본 무결성 미검증 | 있음(이번 검토에서 발견) | 조치 적용(A5), 배포 대기 |

## 대상

- 레포: `TimeLinear/algo-review-api` `main` `248e0b7`(2026-10-02)
- 빌드 `eclipse-temurin:21-jdk`, 실행 `eclipse-temurin:21-jre`, Render `autoDeploy: true`
- 외부 TLS 연결: api.anthropic.com(`LlmClientConfig`, JDK HttpClient, 기본 SSLContext = JDK cacerts), Supabase 풀러(pgjdbc, `sslmode=require`), 빌드 시 services.gradle.org·Maven Central(JDK cacerts)

## 사건 1: eMudhra 중간 CA 키 유출

### 사실관계

- TLS 중간 CA 6개, 코드서명 중간 CA 2개(모두 P-384)의 개인키가 256가지 값 안에서 추측 가능하게 생성됐다(2025-04 발급, 2026-10-17 발견, 10-18~19 폐지).
- 중간 CA를 직접 서명한 루트는 `emSign Root TLS CA - G3`이며, 이 루트는 Java 신뢰 저장소에 없다. 대신 `emSign ECC Root CA - G3`가 이 루트를 교차서명했고, 같은 키로 Sectigo `AAA Certificate Services`가 서명한 버전도 따로 있다(중간 CA 8개, 인증서 16장).
- 따라서 JDK가 침해 체인을 신뢰하는지는 cacerts에 `emSign ECC Root CA - G3` 또는 `AAA Certificate Services`가 있는지로 정해진다.

### 직접 확인한 것 (2026-10-27)

공식 배포 tarball(SHA-256 일치 확인)에서 `lib/security/cacerts`와 `blocked.certs`만 꺼내 시스템 keytool로 읽었다.

| 대상 | emSign ECC Root CA - G3 | AAA Certificate Services | 침해 체인 신뢰 |
| --- | --- | --- | --- |
| Temurin 21.0.12.1+1 (현재 Docker 공식 이미지 `21-jre`·`21-jdk`) | 있음 | 없음 | 신뢰함 |
| Temurin 21.0.13+9 (10/21 GA 바이너리) | 없음(G3·C1·C3 제거, G1만 남음) | 없음 | 신뢰 앵커 없음 |

- Docker 공식 이미지 목록(docker-library/official-images, 10/26)에서 `21-jre`는 `21.0.12.1_1`이다. adoptium/containers의 `dockerfile_bot` 브랜치에 21.0.13+9 반영이 대기 중이며 main에는 아직 없다.
- 21.0.13+9의 `blocked.certs`는 21.0.12.1과 같다. Temurin은 차단 목록이 아니라 루트를 빼는 방식으로 반영했다.

### 원 보고서와 다른 점

1. "Temurin 수정 빌드 없음"은 반만 맞다. 바이너리는 이미 신뢰 앵커를 뺐고, Docker 이미지가 아직 갱신되지 않았을 뿐이다. 그래서 원 보고서의 A1 해제 조건(21.0.13.0.2 이상 + blocked.certs에 지문)은 Temurin에서 오지 않을 수 있다. 해제 조건을 "베이스 이미지 cacerts에 두 루트가 모두 없음"으로 바꿨다.
2. 빌드 단계(A3)는 "낮음·선택"이 아니다. 빌드 이미지도 같은 cacerts를 쓰고, Gradle 배포본 체크섬과 의존성 검증이 없어 빌드 중 중간자 공격이 성공하면 실행 코드 자체가 바뀐다. 가능성은 실행 단계와 같고 피해는 더 크다.
3. 원 보고서의 A1 스크립트는 첫 `keytool -list`가 실패해도 가드가 통과한다(누락 키스토어로 재현). 목록을 파일로 받아 실패 시 빌드를 멈추고, 항목 수(100개 초과)도 확인하도록 바꿨다. 또 이 스크립트는 G3 하나가 아니라 emSign 루트 4개(G1, G3, C1, C3)를 모두 지운다. Temurin 21.0.13이 이미 G3·C1·C3를 뺀 상태로 배포됐으므로 정상 연결에 미칠 영향은 낮다고 보고, 배포 후 `/analyze` 호출로 최종 확인한다.

### 위험도

중간. Render와 Anthropic 사이 경로를 장악해야 해서 가능성은 낮지만, 성공하면 `ANTHROPIC_API_KEY`와 풀이 코드가 유출되고 응답이 조작된다. 키는 2025-04부터 약했으므로 악용 증거는 없어도 Anthropic 콘솔 사용량을 한 번 확인한다.

## 사건 2: Node.js 10/21 보안 패치

영향 없음. 레포와 Docker 이미지에 Node.js, npm, `package.json`이 없다. JDK HttpClient는 응답을 자동으로 압축 해제하지 않고, TLS 호스트 이름 검사는 JSSE가 따로 구현한다. v24.22.1 공식 노트 기준 HIGH는 ID로 5개(CVE-2026-106069, -106072, -105482, -105483, -111497)이며 106072는 106069와 같은 변경이다. 개발 PC에 다른 프로젝트용 Node.js가 있다면 그쪽에서 22.23.4 / 24.22.1 / 26.12.1로 올린다.

## 부수 발견: Supabase 연결 인증서 미검증

D-07의 `sslmode=require`는 pgjdbc에서 암호화만 하고 서버 인증서를 검증하지 않는다. 경로를 가로채면 인증서 위조 없이도 세션을 중계해 분석 로그·캐시를 열람·변조할 수 있다(서버가 SCRAM을 쓰면 비밀번호 자체는 평문으로 넘어가지 않을 수 있다). Supabase 문서는 풀러 주소와 `sslmode=verify-full`, 대시보드에서 받는 루트 인증서를 함께 쓰는 예시를 든다.

## 조치

| ID | 작업 | 상태 | 변경 |
| --- | --- | --- | --- |
| A1 | 실행 이미지 cacerts에서 emSign 루트·AAA 제거 + 가드 | 적용(로컬 커밋) | `Dockerfile`, D-46 |
| A3 | 빌드 이미지에도 A1 적용 | 적용(로컬 커밋) | `Dockerfile`, D-46 |
| A5 | Gradle 배포본 SHA-256 고정 | 적용(로컬 커밋) | `gradle/wrapper/gradle-wrapper.properties`, D-47 |
| A2 | DB 연결을 `verify-full`로 전환 | 대기: 사용자 작업 필요 | 인증서 파일, `Dockerfile`, `render.yaml`, `README.md`, D-07 |
| A4 | Git for Windows 업데이트 | 근거 미확인, 정기 업데이트로 처리 | 없음 |

### A1·A3 검증

- Dockerfile의 두 `RUN` 블록을 그대로 뽑아 dash로 실행(`-cacerts`만 테스트용 키스토어로 대체): 21.0.12.1 cacerts는 118개 → 114개, 21.0.13 cacerts는 118개 → 117개, 두 경우 모두 emSign·AAA 0개. 키스토어를 읽지 못하면 종료 코드 1로 빌드가 멈춘다.
- 실제 이미지 빌드는 이 환경에 Docker가 없어 Render 배포 빌드에서 확인한다. 배포 후 확인할 것: 빌드 로그에서 두 `RUN` 단계 성공, `/actuator/health` UP, `/analyze` 호출이 TLS 오류 없이 성공.
- 해제 조건: 베이스 이미지 cacerts에 `emSign ECC Root CA - G3`와 `AAA Certificate Services`가 모두 없을 것. 블록은 결과가 늘 같으므로 해제 후에도 가드로 남겨도 된다.

### A5 검증

`distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`. gradle.org 체크섬 페이지 값과, gradle/gradle-distributions 릴리스에서 받은 `gradle-9.7.1-bin.zip`을 직접 해시한 값이 같다. 값이 틀리면 래퍼가 다운로드를 거부해 빌드가 실패하는 쪽으로 동작한다. Gradle 버전을 올릴 때 같이 바꿔야 한다. 의존성 jar 검증(`verification-metadata.xml`)은 아직 없다.

### A2 진행 방법 (사용자 작업 포함)

1. Supabase 대시보드 Database Settings의 SSL Configuration에서 루트 인증서(`prod-ca-2021.crt`)를 받아 레포에 넣는다(공개 인증서).
2. 실행 이미지에 복사하고, Render의 `DATABASE_URL`을 `...?sslmode=verify-full&sslrootcert=<이미지 내 경로>`로 바꾼다(Render 대시보드는 사용자가 변경).
3. `render.yaml` 주석, `README.md` 연결 예시, D-07을 같이 고친다. 로컬 docker-compose 기본 URL은 그대로 둔다.
4. 확인: 올바른 루트로 Flyway와 헬스 체크 성공, 다른 루트로는 연결 거부.

## 남은 확인 사항

- [ ] 커밋 push(Render 자동 배포가 시작됨) 후 빌드 로그·`/actuator/health`·`/analyze` 확인
- [ ] Anthropic 콘솔에서 API 키 사용량 이상 여부 확인
- [ ] A2 진행
- [ ] Docker 공식 이미지 `21-jre`가 21.0.13 이상으로 바뀌면 cacerts 재확인(해제 조건)
- 확인하지 못한 주장: Oracle 21.0.13.0.2와 OpenJDK JDK-8395142 반영일, Git for Windows 번들 curl의 Brotli 영향

## 출처

- [eMudhra 영향 중간 CA 목록](https://emudhra.we-have-a-problem.com/impacted-intermediates.json), [루트 목록](https://emudhra.we-have-a-problem.com/roots.json)
- [BellSoft: eMudhra intermediates in Java](https://bell-sw.com/blog/distrusting-compromised-emudhra-intermediates-in-java/)
- [lilting.ch: OpenJDK/Temurin 테스트](https://lilting.ch/en/articles/openjdk-cve-2026-91403-emudhra-cacerts)
- [BleepingComputer: Mozilla, Google, Apple block eMudhra CAs](https://bleepingcomputer.com/news/security/mozilla-google-apple-block-compromised-emudhra-ca-certificates)
- [Node.js 24.22.1 릴리스 노트](https://nodejs.org/en/blog/release/v24.22.1)
- [Supabase SSL Enforcement](https://supabase.com/docs/guides/platform/ssl-enforcement), [Supabase psql 연결](https://supabase.com/docs/guides/database/psql)
- [adoptium/temurin21-binaries](https://github.com/adoptium/temurin21-binaries), [adoptium/containers](https://github.com/adoptium/containers), [docker-library/official-images](https://github.com/docker-library/official-images)
- [Gradle release checksums](https://gradle.org/release-checksums/)
