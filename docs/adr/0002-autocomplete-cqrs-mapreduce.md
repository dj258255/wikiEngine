# 0002. 자동완성 — CQRS + MapReduce + Redis Flat KV

- **상태**: Accepted
- **결정일**: 2026-02-08
- **관련 ADR**: [#0001](./0001-lucene-direct-vs-elasticsearch.md), [#0006](./0006-tiered-cache-consistent-hashing.md)

## Context

자동완성은 **사용자 한 글자 입력마다** 호출된다. 12 M 게시글이 있는 커뮤니티에서:

- 타이핑 지연을 견딜 수 있는 응답 = **P95 < 100 ms** 가 현실적 상한
- 한글 입력의 특수성: "ㅈㅂ" → "자바", "ㅋㅍㅌ" → "컴퓨터" (자모만 입력해도 매칭)
- 인기 검색어 기반 — 단순 "타이틀 prefix" 가 아니라 **실제로 사람들이 친 검색어** 가 추천되어야 함

요구사항:

- 응답: O(1) 또는 그에 가까운 시간 복잡도
- 데이터 갱신: 1 시간 이내에 새 검색 트렌드 반영
- 한글 자모 분리·재조립 지원
- 영어 prefix 도 동작 ("jav" → "java")

## Decision Drivers

1. **응답 시간 P95 < 100 ms** — 사용자가 타이핑하는 동안 응답해야 함
2. **데이터 신선도 ≤ 1 h** — 실시간일 필요는 없음 (커뮤니티 트렌드는 분 단위 변동성 낮음)
3. **운영 단순성** — 별도 검색 인프라(Trie/RadixTree 서비스) 추가 없이
4. **메모리 효율** — 12 GB 머신에서 자동완성 전용으로 쓸 수 있는 메모리 제한적

## Considered Options

### Option A: 단일 Lucene PrefixQuery

- 장점: 인프라 추가 없음, Lucene 이 이미 있으니 즉시 사용 가능
- 단점:
  - PrefixQuery 는 **모든 prefix 일치 term 을 후보로** 만든 뒤 BM25 계산 → 12 M 인덱스에서는 P95 수백 ms 이상
  - 인기도(검색 빈도) 가중치를 주려면 추가 필드/쿼리 필요
  - 자모 검색은 별도 분석기 필요

### Option B: Trie / Radix Tree 서비스 (Aerospike, MeiliSearch, 자체 구현)

- 장점: 자료구조 자체가 prefix 검색에 최적화 — O(k) where k = prefix 길이
- 단점:
  - **별도 서비스 운영 부담** — MeiliSearch 등은 또 다른 프로세스 + RAM
  - 자체 구현은 가능하지만 메모리 사용량이 큼 (12 M 검색어 Trie 가 수 GB)
  - persistence/restart 시 재구축 시간 길음

### Option C: Redis Flat KV + 사전 계산 (선택)

- 장점:
  - **읽기 = `GET prefix` → O(1)**. Redis 가 이미 캐시로 사용 중이라 인프라 추가 0.
  - 자모/영어/한글 prefix 를 **모두 키로 저장** 하면 분기 없이 단일 GET 으로 처리
  - 인기도 가중치는 사전 계산 단계에서 한 번에 처리 → 응답 단계 비용 0
- 단점:
  - 사전 계산 파이프라인(MapReduce 형태)이 필요
  - 갱신 주기 = 배치 주기 (실시간 아님)
  - 키 공간 폭발 가능성 → 접두사 길이 제한·Top-K 제한 필요

## Decision

**Option C — Redis Flat KV + Spring Batch MapReduce 사전 계산** 선택.

CQRS 패턴 적용:

```
[Command / Write Path]
사용자 검색 → SearchLogCollector → MySQL search_logs INSERT (트랜잭션 외부)

[Batch / MapReduce — 1h 주기]
search_logs 24h 윈도우
  ↓ Map: (keyword, count) tuple 추출
  ↓ Shuffle: 접두사 1~10자로 분해 (한글, 영어, 자모 모두)
  ↓ Reduce: 접두사별 Top-K 키워드 (K=10, 인기도+최신성 가중치)
  ↓ Write: Redis SET "ac:<prefix>" → JSON Top-K

[Query / Read Path]
사용자 입력 → AutocompleteService → Redis GET "ac:<prefix>"
                                    ↓ miss
                                    Lucene title_raw PrefixQuery (fallback)
```

### 왜 CQRS?

검색 로그 쓰기 빈도는 검색 쿼리 빈도와 동일(검색 1회 = 로그 1회). 읽기는 **타이핑 한 글자마다** 발생 → 보통 검색 1회당 자동완성 5~10회 호출. 따라서:

- **읽기는 메모리(Redis) — O(1)**
- **쓰기는 MySQL** (이력 누적, 분석 용도 겸용)

읽기 모델과 쓰기 모델을 다른 스토리지에 두는 것 = CQRS.

### 왜 MapReduce?

24 h 윈도우의 search_logs 가 수십만~수백만 row → 단일 SQL 로는 메모리 OOM 위험. 청크 단위 Map → Reduce 가 자연스럽다. Spring Batch 가 이 패턴을 표준으로 지원.

## Consequences

### Positive

- **P95 < 100 ms 달성** (실측 43 ms 평균 / 99 ms P95, k6 100 VU 기준)
- 인프라 추가 0 — Redis 는 이미 캐시용으로 운영 중
- 한글 자모·영어·한글 prefix 가 같은 `GET` 호출 하나로 처리
- 인기도 가중치, 최신성 가중치 등을 **배치 단계에서만 처리** → 응답 경로는 단순 KV 조회

### Negative

- **신선도 1 h 지연** — 실시간 트렌드 반영 안 됨 (예: 뉴스 속보 검색어가 1시간 뒤에 자동완성에 뜸)
- **콜드 스타트 문제** — search_logs 가 비어 있는 초기에는 추천 가능한 키워드가 적음 → Lucene PrefixQuery fallback 필요 (현재 구현)
- 키 공간 — 12 M 게시글 제목 + 검색 로그 prefix → 수십만 키. Redis 메모리 사용량 모니터링 필요.

### Neutral

- 배치 실패 시 직전 결과 유지 — 가용성에 큰 영향 없음
- Redis 샤딩 ([ADR-0006](./0006-tiered-cache-consistent-hashing.md)) 영향 받음 — 자동완성 키는 prefix 가 키이므로 샤드 분산이 자연스러움

## Validation

- **응답 P95 < 100 ms** — 현재 99 ms (충족, 임계값 부근)
- **데이터 신선도 ≤ 1 h** — Spring Batch 트리거 로그에서 확인
- **Redis hit rate ≥ 95%** — Lucene fallback 호출 카운트 모니터링
- **자모 검색 정확도** — 수동 회귀 테스트 ("ㅈㅂ" → "자바" 포함)

만약 신선도 요구가 분 단위로 내려가면 Kafka stream 기반 incremental update 로 변경 검토 (`Supersedes #0002`).

## References

- 구현: `backend/src/main/java/com/wiki/engine/post/internal/search/`
  - `SearchLogCollector.java` — Command path
  - `AutocompleteBuildJob.java` — Spring Batch MapReduce
  - `AutocompleteService.java` — Query path
- 자모 변환: `JamoUtils` (Hangul Unicode 분해)
- 관련 설계 문서: [project_autocomplete_system_design](../../) (메모리)
