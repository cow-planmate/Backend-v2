# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 의존성 레이어 캐싱: 빌드 스크립트 먼저 복사
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 bootJar (테스트는 CI에서 별도, 이미지 빌드시에는 제외)
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 헬스체크용 curl + 비루트 유저
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app app

COPY --from=build /app/build/libs/*.jar app.jar
RUN chown app:app app.jar
USER app

# 8080: 외부 HTTP API / 9090: 내부 서비스 간 gRPC (외부 노출 안 함)
EXPOSE 8080 9090
ENV JAVA_OPTS="-Xmx256m -Xms64m -XX:+UseSerialGC -Duser.timezone=Asia/Seoul"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
