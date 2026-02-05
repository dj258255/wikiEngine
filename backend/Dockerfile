# 멀티스테이지 빌드: 빌드 결과물만 최종 이미지에 포함
# Java 25 + Spring Boot 4.0.1

# --- 빌드 스테이지 ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

# 의존성 먼저 다운로드 (레이어 캐싱)
RUN ./gradlew dependencies --no-daemon || true

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# --- 실행 스테이지 ---
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
