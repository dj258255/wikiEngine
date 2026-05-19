# Changelog

이 프로젝트의 주요 변경 사항을 기록합니다. 포맷은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/) 1.1.0 을 따릅니다.

> 정식 SemVer 릴리스 대신 개발 단계를 의미하는 **Phase 번호**를 마일스톤으로 사용합니다.

---

## [Unreleased]

### Added
- JVM 튜닝 실험 인프라 — JDK 이미지 + GC log + NMT(Native Memory Tracking) + heap dump 자동화 (2026-04-16)
- 메인 페이지 글쓰기 버튼, Tiptap 리치 텍스트 에디터, 카테고리 드롭다운 (리퀴드 글래스 스타일) (2026-04-10)

### Changed
- common 컨벤션 통일 — 작성자 닉네임 노출, 좋아요 응답 구조 개선 (2026-04-10)
- Spring Batch 6.0+ 호환 — deprecated `JobLauncher` → `JobOperator` 마이그레이션 (2026-04-05)

### Fixed
- 위키 파서 — 각주 섹션 제거 + 인포박스 잔해 정리 + 하단 빈 섹션 정리 (2026-04-10)
- 위키 내부 링크 비활성화 — 텍스트만 표시, 클릭 불가 처리 (2026-04-10)
- UserMenu 드롭다운 z-index — 카테고리 탭 위로 투명하게 보이는 문제 (2026-04-11)
- `getPost` 비로그인 사용자에서 `@CurrentUser` 예외 → `SecurityContext` 직접 확인 (2026-04-10)
- 카테고리 탭 가로 스크롤 제거 + 좌우 화살표 + 순서 보정 (2026-04-10)

---

## [Phase 21] — 2026-03-27 — RAG 검색 요약 + Rate Limit

### Added
- **Spring AI + Gemini 3.1 Flash Lite 기반 RAG 검색 결과 요약** (SSE 스트리밍)
- AI 요약 카드의 thumbs up/down + 카테고리별 피드백 수집 (`ai_summary_feedback` 테이블, Flyway V3)
- Rate Limit (검색·요약 API)
- Redis 캐싱 — AI 요약 결과 재사용
- Grafana LLM 모니터링 대시보드 (토큰 사용량, 응답 지연, 실패율)

### Related ADR
- [ADR-0001](docs/adr/0001-lucene-direct-vs-elasticsearch.md) (Lucene 직접 임베드 기반)

---

## [Phase 20] — 2026-03-27 — 콘텐츠 필터링 + 자동완성 개선

### Added
- **Aho-Corasick 알고리즘** 기반 금칙어 필터링 (`banned_words` 테이블, Flyway V2) — O(n) 멀티 패턴 매칭
- `banned_words_ko.txt`, `banned_words_en.txt` 시드
- `posts.blinded` 컬럼 추가 (Flyway V4) — 관리자 게시글 블라인드 처리
- 자동완성 negative caching — 금칙어 포함 검색어 캐시 제외

### Changed
- 자동완성 후보 산정 로직 개선 — 인기도/최신성 가중치 반영

---

## [Phase 19] — 2026-03-24 ~ 2026-03-30 — LTR + Facet + 카테고리 자동 분류

### Added
- **LTR(Learning to Rank) 파이프라인** — XGBoost LambdaMART, 14 피처 (BM25 3필드 + 태그 중복 + 문서 시그널), Two-Phase Ranking (2026-03-29)
- **클릭 로그 인프라** — Kafka `search.clicks` 토픽 + Beacon API dwell time, implicit feedback 수집 (Flyway V5, 2026-03-30)
- **Lucene Faceted Search** — 카테고리별 매칭 건수 집계, 검색 결과 사이드바 facet (2026-03-26)
- **MoreLikeThis 기반 카테고리 자동 추천** — 새 게시글 작성 시 (2026-03-24)
- 주제별 카테고리 28개 + 키워드 기반 자동 분류 (`categories`, `category_keywords` 테이블, Flyway V1 시드, 2026-03-24)
- Gemini LLM-as-a-Judge 학습 데이터 자동 생성 (200 쌍, NDCG@10 CV +4.8 %p)

### Related ADR
- [ADR-0003](docs/adr/0003-xgboost4j-native-binding.md) (XGBoost4J 네이티브 바인딩, ONNX 우회)

---

## [Phase 18] — 2026-03-24 — 검색 품질 향상

### Added
- **동의어 확장** — DB 기반 쿼리 타임 ("AI" ↔ "인공지능", "자바" ↔ "Java", `synonyms` 테이블 Flyway V1 시드)
- **오타 교정** — Lucene `DirectSpellChecker` + "혹시 OO을 찾으셨나요?" UI
- `UnifiedHighlighter` 적용 — 검색 결과 snippet 하이라이팅
- 재색인 인프라 정비

---

## [Phase 17] — 2026-03-24 — 위키 마크업 정리 + 카테고리 필터

### Added
- 카테고리 검색 필터 UI + API
- 메인 화면 개선

### Changed
- 나무위키 / 영문 위키 마크업 제거 후 plain text 로 인덱싱 (`snippetSource` 필드 별도 저장)

---

## [Phase 15] — 2026-03-23 — Redis 샤딩

### Added
- **Redis Consistent Hashing 3-Shard** — virtual node 150 개로 분포 편향 완화
- 노드 추가/제거 시 키 재배치 최소화
- `redis_exporter` 샤드별 모니터링

### Related ADR
- [ADR-0006](docs/adr/0006-tiered-cache-consistent-hashing.md)

---

## [Phase 14] — 2026-03-21 — CDC + Spring Modulith 이벤트

### Added
- **Spring Modulith 이벤트 기반 디커플링** — `PostService` → `PostEvent` 발행, `@TransactionalEventListener(AFTER_COMMIT)`
- **Debezium + Kafka 4.2 (KRaft) CDC** — MySQL binlog → Kafka topic `posts.cdc`
- 서버 1, 2 인덱스 동기화 이중화 (이벤트 + CDC)

### Related ADR
- [ADR-0005](docs/adr/0005-index-sync-modulith-cdc.md)

---

## [Phase 13] — 2026-03-20 — App 스케일아웃

### Added
- 서버 2 App 인스턴스 추가 (서버 1 + 서버 2)
- Nginx upstream Round Robin
- Spring Boot Grafana 대시보드 인스턴스별 분리

---

## [Phase 12] — 2026-03-19 ~ 2026-03-20 — MySQL Replication + R/W Split

### Added
- **MySQL Primary-Replica** — Binlog/GTID 기반 복제 (서버 1 Primary, 서버 2 Replica)
- **HikariCP 풀 분리** — Primary / Replica 각각 별도 풀
- **DataSource Read/Write 라우팅** — `Nginx L7` HTTP 메서드 기반 라우팅 (GET → RR, 쓰기 → Primary)
- Replication 모니터링 대시보드 + 알림

### Related ADR
- [ADR-0007](docs/adr/0007-nginx-rw-split-mysql-replication.md)

---

## [Phase 11] — 2026-03-18 ~ 2026-03-19 — Redis L2 캐시

### Added
- **2 단 캐시** — Caffeine L1 (in-process) + Redis L2
- `@Cacheable` → `TieredCacheService` 추상화
- **자동완성** — 인메모리 Trie → **Redis flat KV** 전환 (O(1) 응답)
- Grafana 캐시 hit/miss 대시보드 + 알림 규칙

### Related ADR
- [ADR-0002](docs/adr/0002-autocomplete-cqrs-mapreduce.md), [ADR-0006](docs/adr/0006-tiered-cache-consistent-hashing.md)

---

## [Phase 10] — 2026-03-16 — Tomcat + JVM 튜닝

### Changed
- Tomcat 스레드 풀 크기 조정 (부하 테스트 기반)
- JVM heap 사이즈 + G1GC 옵션 튜닝

---

## [Phase 9] — 2026-03-15 — 자동완성 (초기 버전)

### Added
- 검색 로그 수집 인프라 (`search_logs` 테이블)
- **인메모리 Trie 자동완성** (Phase 11 에서 Redis flat KV 로 대체됨)
- **한글 자모 분해 자동완성** — "ㅈㅂ" → "자바"
- 검색 API `Page` → `Slice` 전환 + snippet 반환

---

## [Phase 1 ~ 8] — 2026-02-04 ~ 2026-03-14 — 초기 구축

### Added
- **Spring Boot 4.0.1 / Spring Framework 7 / Java 25 / Spring Modulith 2.0.2** 기반 프로젝트 골격
- Next.js + TypeScript 프론트엔드
- JWT 인증 + 회원가입/로그인
- 게시판 CRUD (`post`, `user`, `category` 모듈)
- **OCI Free Tier** 3 대 서버 (이후 4 대로 확장) **Ansible** 자동 배포
  - ARM App + AMD Loki/Grafana + AMD Prometheus 역할 분리
  - `ansible-vault` 시크릿 + Jinja2 템플릿 IP/환경변수 주입
- Nginx 리버스 프록시 + Let's Encrypt HTTPS + Basic Auth
- GitHub Actions CI/CD — QEMU/Buildx ARM64 크로스 빌드 → GHCR push
- Prometheus + Loki + Promtail + MySQL Exporter 모니터링 스택
- **Flyway** DB 마이그레이션 — 운영은 `validate`, 로컬은 `ddl-auto: update`
- 12 M 건 공개 데이터셋 임포트 (나무위키, 위키백과 ko/en, 뉴스, 웹텍스트, C4 ko)
- k6 부하 테스트 환경

---

## 변경 분류 참고

| 분류 | 의미 |
|------|------|
| **Added** | 새 기능 |
| **Changed** | 기존 기능 변경 (호환성 영향 가능) |
| **Deprecated** | 곧 제거 예정 (아직 동작) |
| **Removed** | 이미 제거됨 |
| **Fixed** | 버그 수정 |
| **Security** | 보안 관련 변경 |

[Keep a Changelog]: https://keepachangelog.com/ko/1.1.0/
