# 1단계: 빌드 (JDK + Gradle wrapper). 테스트는 CI/로컬에서 돌리고 여기서는 jar만 만든다.
FROM eclipse-temurin:21-jdk AS build
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
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /app/build/libs/algo-review-api-0.0.1-SNAPSHOT.jar app.jar
USER app
# MaxRAMPercentage=50 -> 힙 최대 256MB. 메타스페이스·스레드 스택을 더해도 512MB 안에 들어간다.
# SerialGC: 코어 1개·메모리 작은 환경에서 메모리 오버헤드가 가장 작다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
