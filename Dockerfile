# 1단계: 빌드 (JDK + Gradle wrapper). 테스트는 CI/로컬에서 돌리고 여기서는 jar만 만든다.
FROM eclipse-temurin:21-jdk AS build
# 빌드 단계도 Gradle 배포본과 의존성을 TLS로 받으므로 같은 조치를 한다.
# eMudhra 중간 CA 키 유출(2026-10) 대응(docs/decisions.md D-46, docs/security/2026-10-emudhra.md).
# 침해 체인의 신뢰 앵커(emSign 루트, Sectigo AAA Certificate Services)를 JDK cacerts에서 지우고,
# 남아 있거나 cacerts를 못 읽으면 빌드를 실패시킨다. 몇 번 실행해도 결과가 같으므로 가드로 계속 둔다.
# 해제 조건: 베이스 이미지 cacerts에 emSign ECC Root CA - G3와 AAA Certificate Services가 모두 없을 것
#           (Temurin 21.0.13+9 바이너리는 이미 충족. Docker 공식 이미지 21-jre가 21.0.13 이상으로 갱신되면 재검토)
RUN keytool -list -v -cacerts -storepass changeit > /tmp/ca.txt \
 && awk '/^Alias name:/{a=substr($0,13)} /^Owner:.*(emSign|AAA Certificate Services)/{print a}' /tmp/ca.txt \
    | while read -r a; do keytool -delete -cacerts -storepass changeit -alias "$a"; done \
 && keytool -list -v -cacerts -storepass changeit > /tmp/ca.txt \
 && [ "$(grep -c '^Alias name:' /tmp/ca.txt)" -gt 100 ] \
 && ! grep -qE 'Owner:.*(emSign|AAA Certificate Services)' /tmp/ca.txt \
 && rm /tmp/ca.txt
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
# Windows 체크아웃에서 CRLF가 섞여 들어와도 리눅스에서 실행되도록
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
# 의존성 레이어를 먼저 받아 두면 소스만 바뀐 빌드에서 캐시가 유지된다
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# 2단계: 실행 (JRE만). Render 무료 인스턴스(512MB)에서 뜨도록 JVM 메모리를 제한한다.
FROM eclipse-temurin:21-jre
# eMudhra 중간 CA 키 유출(2026-10) 대응(docs/decisions.md D-46, docs/security/2026-10-emudhra.md).
# 침해 체인의 신뢰 앵커(emSign 루트, Sectigo AAA Certificate Services)를 JDK cacerts에서 지우고,
# 남아 있거나 cacerts를 못 읽으면 빌드를 실패시킨다. 몇 번 실행해도 결과가 같으므로 가드로 계속 둔다.
# 해제 조건: 베이스 이미지 cacerts에 emSign ECC Root CA - G3와 AAA Certificate Services가 모두 없을 것
#           (Temurin 21.0.13+9 바이너리는 이미 충족. Docker 공식 이미지 21-jre가 21.0.13 이상으로 갱신되면 재검토)
RUN keytool -list -v -cacerts -storepass changeit > /tmp/ca.txt \
 && awk '/^Alias name:/{a=substr($0,13)} /^Owner:.*(emSign|AAA Certificate Services)/{print a}' /tmp/ca.txt \
    | while read -r a; do keytool -delete -cacerts -storepass changeit -alias "$a"; done \
 && keytool -list -v -cacerts -storepass changeit > /tmp/ca.txt \
 && [ "$(grep -c '^Alias name:' /tmp/ca.txt)" -gt 100 ] \
 && ! grep -qE 'Owner:.*(emSign|AAA Certificate Services)' /tmp/ca.txt \
 && rm /tmp/ca.txt
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /app/build/libs/algo-review-api-0.0.1-SNAPSHOT.jar app.jar
USER app
# MaxRAMPercentage=50 -> 힙 최대 256MB. 메타스페이스·스레드 스택을 더해도 512MB 안에 들어간다.
# SerialGC: 코어 1개·메모리 작은 환경에서 메모리 오버헤드가 가장 작다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
