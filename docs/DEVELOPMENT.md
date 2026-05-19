# Development Guide

로컬 개발 환경 셋업·실행·테스트·디버깅 가이드.

> 운영 배포(Ansible)는 별도 문서. 이 문서는 **개발자 PC** 에서 wikiEngine 을 띄우는 데 집중합니다.

---

## 목차

- [전제 조건](#전제-조건)
- [한 줄 실행](#한-줄-실행)
- [환경 변수 (.env.dev)](#환경-변수-envdev)
- [데이터베이스 마이그레이션](#데이터베이스-마이그레이션)
- [Lucene 인덱스 초기 빌드](#lucene-인덱스-초기-빌드)
- [테스트 실행](#테스트-실행)
- [JVM 튜닝 / 진단](#jvm-튜닝--진단)
- [자주 발생하는 문제](#자주-발생하는-문제)

---

## 전제 조건

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | 25 (Temurin 권장) | `java --version` |
| Gradle | 9.3.1 (Wrapper 포함, 별도 설치 불필요) | `./gradlew --version` |
| Docker | 24+ | MySQL/Redis/Kafka 컨테이너 실행용 |
| Node.js | 20+ | 프론트엔드 (선택) |
| MySQL Client | 8.0+ | 데이터 확인용 (선택) |

`brew` 가 있다면:

```bash
brew install --cask temurin@25
brew install docker
brew install node@20
```

---

## 한 줄 실행

```bash
git clone <repo>
cd wikiEngine

# 1) 인프라 (MySQL, Redis, Prometheus, Grafana ...) 컨테이너 기동
cd backend
cp .env.dev.example .env.dev   # 값 채우기 — 아래 환경 변수 섹션 참조
make dev-up

# 2) 백엔드 실행 (IDE 또는 CLI)
./gradlew bootRun

# 3) 프론트엔드 (선택)
cd ../frontend
npm install && npm run dev
```

- 백엔드: `http://localhost:8080`
- 프론트엔드: `http://localhost:3000`
- Grafana: `http://localhost:3001` (admin/admin)
- Prometheus: `http://localhost:9090`

---

## 환경 변수 (.env.dev)

`backend/.env.dev` 파일은 Docker Compose 와 IDE 양쪽에서 사용합니다. 핵심 항목:

```dotenv
# --- MySQL ---
DB_PRIMARY_HOST=localhost
DB_REPLICA_HOST=localhost
DB_PORT=3306
DB_NAME=wikiengine
DB_USERNAME=wiki
DB_PASSWORD=changeme
MYSQL_ROOT_PASSWORD=changeme
DB_PRIMARY_POOL_SIZE=10
DB_REPLICA_POOL_SIZE=10

# --- Redis ---
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=changeme
REDIS_SHARDING_ENABLED=false   # 로컬은 단일 Redis 권장

# --- Kafka (선택, CDC 테스트 시) ---
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# --- JPA / Flyway ---
JPA_DDL_AUTO=update            # 로컬은 update, 운영은 validate
JPA_SHOW_SQL=true
FLYWAY_ENABLED=true
HIBERNATE_BATCH_SIZE=50
HIBERNATE_STATISTICS=false

# --- JWT ---
JWT_SECRET=local-only-256bit-secret-for-development
JWT_ACCESS_TOKEN_EXPIRY=3600
JWT_REFRESH_TOKEN_EXPIRY=604800

# --- Lucene ---
LUCENE_INDEX_PATH=./data/lucene

# --- AI (선택, RAG 동작 확인 시) ---
GEMINI_API_KEY=
```

IntelliJ 에서 `Run Configurations → Environment Variables → "Read from .env file"` 로 자동 로딩 가능합니다.

---

## 데이터베이스 마이그레이션

[Flyway](https://flywaydb.org/) 가 `backend/src/main/resources/db/migration/` 의 SQL 을 순서대로 실행합니다.

```
V1__seed_data.sql              # synonyms, categories, category_keywords 시드
V2__banned_words.sql           # 금칙어 (Phase 20)
V3__ai_summary_feedback.sql    # RAG 피드백 (Phase 21)
V4__add_blinded_to_posts.sql   # 블라인드 컬럼
V5__create_click_logs.sql      # 클릭 로그 (LTR implicit feedback)
```

- 앱 기동 시 자동 실행 (`spring.flyway.enabled=true`)
- 운영 환경은 `JPA_DDL_AUTO=validate` + Flyway 만으로 스키마 관리
- 새 마이그레이션 추가 시: `V6__설명.sql` 로 다음 번호 사용 (편집 금지, append-only)

> 주의: 운영 MySQL Replication 이 끊긴 경우 Flyway DDL 이 Replica 로 전파되지 않을 수 있습니다. 수동 적용 필요.

---

## Lucene 인덱스 초기 빌드

12M 건 전체 인덱스는 39 GB, 로컬에서는 부분 빌드를 권장합니다.

```bash
# 1) 위키 임포트 (HuggingFace 데이터셋 한 개 선택)
curl -X POST http://localhost:8080/api/admin/wiki/import \
  -H "Content-Type: application/json" \
  -d '{"source": "namuwiki", "limit": 10000}'

# 2) 전체 재색인 (DB → Lucene)
curl -X POST http://localhost:8080/api/admin/lucene/reindex
```

- 빌드 중 진행률은 로그(`INFO LuceneIndexService`)에 표시됩니다.
- 인덱스 위치: `LUCENE_INDEX_PATH` (기본 `./data/lucene`)
- 운영 인덱스 동기화: `backend/scripts/sync-index.sh` (rsync, 별도 호스트 키 필요)

---

## 테스트 실행

```bash
# 전체 (41건 — PostControllerTest 32 + AI 9)
./gradlew test

# 특정 클래스
./gradlew test --tests PostControllerTest

# 특정 메서드
./gradlew test --tests PostControllerTest.shouldReturnPostList
```

### 테스트 환경 주의사항 (Spring Boot 4)

- `@WebMvcTest` import 경로: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (Boot 3 과 다름)
- `@MockBean` 은 deprecated → `@MockitoBean` 사용
- ObjectMapper 대신 `@Autowired JsonMapper jsonMapper` (Jackson 3)
- `PostController` 테스트 시 Mock 필요한 빈: `PostService`, `ViewCountService`, `RagService`, `AiSummaryDecisionService`, `AiFeedbackService`, `JwtTokenProvider`, `TokenBlacklist`
- 테스트용 설정: `src/test/resources/application.yml` (환경변수 없이 직접 값)

### Spring Modulith 모듈 검증

```bash
./gradlew test --tests ModulithTest
```

`auth → user` 같은 금지된 방향의 의존이 있으면 컴파일이 통과해도 이 테스트에서 실패합니다.

---

## JVM 튜닝 / 진단

운영 컨테이너에는 진단 도구가 들어 있는 JDK 이미지를 사용합니다 (`eclipse-temurin:25-jdk`). 로컬에서도 같은 방식으로 검증 가능:

```bash
# GC 로그 + heap dump 경로 지정 실행
JAVA_OPTS="-Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -Xlog:gc*:file=./logs/gc.log:time,uptime,level,tags \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=./logs/heap.hprof \
  -XX:NativeMemoryTracking=summary" \
./gradlew bootRun
```

### 진단 명령어

```bash
# 프로세스 PID 확인
jps -l | grep wiki

# GC 통계
jstat -gc <PID> 1000     # 1초 간격

# Native Memory Tracking
jcmd <PID> VM.native_memory summary

# Heap dump 즉시 생성
jcmd <PID> GC.heap_dump ./logs/manual.hprof

# Thread dump
jcmd <PID> Thread.print > ./logs/threads.txt
```

heap dump 분석은 [Eclipse MAT](https://eclipse.dev/mat/) 추천.

---

## 자주 발생하는 문제

### 1. `@WebMvcTest` 에서 `MissingBeanException`

원인: Boot 3 임포트 경로 사용. `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` 로 변경.

또한 `testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'` 의존성이 별도로 필요합니다.

### 2. Lucene 인덱스 검색 시 빈 결과

원인: PostEvent 가 발행됐지만 LuceneIndexEventHandler 가 트랜잭션 외부에서 실행되어 commit 시점 차이. `LuceneSearchService` 가 `maybeRefresh()` 를 호출하는지 확인 (NRT). 자세한 설계는 [ADR-0005](./adr/0005-index-sync-modulith-cdc.md).

### 3. 자동완성 결과가 없음

원인: Spring Batch job 이 아직 안 돌았거나 검색 로그가 비어 있음.

```bash
# 수동 트리거
curl -X POST http://localhost:8080/api/admin/autocomplete/rebuild
```

### 4. MySQL `Specified key was too long` 에러

원인: `utf8mb4` + 인덱스 컬럼 길이. Flyway 마이그레이션에서 `VARCHAR(191)` 이하 사용 또는 prefix index 적용.

### 5. Redis Sharding 켰는데 키가 한쪽으로 몰림

원인: Consistent Hashing 의 virtual node 수가 너무 적음. `RedisShardConfig` 에서 `virtualNodes` 확인 (기본 150 권장). 자세한 내용 [ADR-0006](./adr/0006-tiered-cache-consistent-hashing.md).

---

## 관련 문서

- 시스템 전체 구조: [ARCHITECTURE.md](./ARCHITECTURE.md)
- 기술 결정의 배경: [adr/](./adr/README.md)
- 부하 테스트 결과: [BENCHMARK_REPORT.md](./BENCHMARK_REPORT.md)
