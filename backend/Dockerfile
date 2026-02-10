# syntax=docker/dockerfile:1
# 멀티스테이지 빌드: 빌드 결과물만 최종 이미지에 포함
# Java 25 + Spring Boot 4.0.1

# --- 빌드 스테이지 ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

# Gradle 캐시 마운트로 의존성 다운로드 캐시 유지
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -x test

# --- 실행 스테이지 ---
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
