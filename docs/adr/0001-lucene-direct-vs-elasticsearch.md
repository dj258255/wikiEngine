# 0001. Lucene 직접 임베드 (Elasticsearch 미사용)

- **상태**: Accepted
- **결정일**: 2026-01-15
- **관련 ADR**: [#0004](./0004-disjunction-max-query-cjk.md) (쿼리 구조), [#0005](./0005-index-sync-modulith-cdc.md) (인덱스 동기화)

## Context

wikiEngine 은 1,215 만 건의 문서(나무위키 + 위키백과 ko/en + 뉴스 + 웹텍스트)를 색인하고 BM25 검색 + Learning-to-Rank 를 제공해야 한다. 동시에 **인프라 예산은 OCI Free Tier** 만 사용한다.

핵심 제약:

- **메모리**: 서버 1대 = ARM 2 vCPU / **12 GB RAM** × 2 대. Elasticsearch 단일 노드 권장 사양(최소 8 GB heap)을 띄우면 같은 머신에서 Spring Boot 앱과 MySQL 을 같이 돌릴 수 없다.
- **운영 인력**: 1 인. ES 클러스터 헬스/샤드/노드 운영 부담을 감당할 수 없다.
- **데이터 규모**: 12 M 건 / 39 GB 인덱스. Solr/ES 의 분산 능력이 필요한 규모는 아니다 — 단일 JVM 으로도 충분.
- **JVM 통합**: Spring Boot 앱이 이미 JVM 위에서 돈다. Lucene 은 같은 JVM 안에서 in-process 호출 가능 → RPC 오버헤드 0.

## Decision Drivers

1. **메모리 footprint** — 12 GB 안에 앱 + DB + 검색이 다 들어가야 한다 (최우선)
2. **운영 단순성** — 별도 클러스터 운영 없이
3. **검색 품질에 대한 통제권** — 한국어(Nori) 토크나이저, n-gram 필드, LTR re-rank 등 커스터마이즈 자유도
4. **응답 지연** — RPC 없는 in-process 호출로 P95 안정화

## Considered Options

### Option A: Elasticsearch 단일 노드

- 장점: 분산 검색·인덱싱 자동 처리, REST API 표준, 풍부한 분석 기능 (aggregation, percolator)
- 단점:
  - **JVM heap 8 GB 최소 권장** → 12 GB 머신에서 앱 공존 불가
  - 클러스터 메타데이터·헬스체크·shard rebalance 등 운영 부담
  - LTR 플러그인은 있지만 XGBoost4J 와의 직접 통합은 추가 작업 필요
- 비용/리스크: OCI 인스턴스 추가 필요 → Free Tier 초과

### Option B: Apache Solr

- 장점: 단일 인스턴스도 비교적 가볍게 운영 가능, schema-first 모델로 한국어 분석 설정 용이
- 단점:
  - 여전히 별도 프로세스 → JVM 두 개 운영
  - 커뮤니티 활성도가 ES 대비 낮음 (2026 기준)
  - 커스텀 점수 함수(LTR)를 위해 ZooKeeper/Solrcloud 까지 가야 하는 경우 많음
- 비용/리스크: 추가 인스턴스 또는 같은 머신에서 RAM 경쟁

### Option C: Lucene 직접 임베드 (선택)

- 장점:
  - **앱과 같은 JVM** → heap 공유, 추가 프로세스 0
  - Nori 형태소 분석기, n-gram 필드, Custom Analyzer 등 모든 분석 단계 직접 제어
  - XGBoost4J 와 in-process 통합 (LTR re-rank 가 단순 Java 호출)
  - NRT (Near Real-Time) 검색 직접 제어 — `IndexWriter.maybeRefresh()` 호출 시점 통제
- 단점:
  - 분산 검색·복제는 직접 구현 (rsync + 이벤트 + CDC) → [ADR-0005](./0005-index-sync-modulith-cdc.md) 에서 별도 결정
  - 클러스터 관리 도구 부재 (Kibana 같은 GUI 없음)
  - 인덱스 메타데이터 관리·백업·롤링 스키마 변경을 손으로 해야 함

## Decision

**Option C — Lucene 직접 임베드** 선택.

핵심 근거는 **메모리 footprint**. 12 GB 머신에서 Spring Boot 앱(약 2 GB heap) + MySQL(약 4 GB) + Lucene 인덱스 메모리맵(약 2~3 GB) 이 같이 살아야 하는데, ES 를 추가하면 단일 노드라도 8 GB heap 권장이라 즉시 불가능하다. 결정 기준 #1 에서 ES/Solr 는 탈락.

부가적으로, 12 M 건은 단일 JVM Lucene 으로 충분히 다룰 수 있는 규모다. 우리는 분산 검색이 아니라 **단일 인덱스 + Replica rsync** 모델로 충분.

## Consequences

### Positive

- 서버 1 대 = 앱 + MySQL + Lucene 모두 12 GB 안에 공존. **인프라 비용 0**.
- BM25 점수 → XGBoost re-rank 가 **단일 Java 메서드 호출 체인** — RPC 없음.
- Nori 한국어 분석기, n-gram, 자모 변환 등 **모든 분석 파이프라인을 코드 레벨에서 제어**.
- 인덱스 NRT refresh 시점을 앱이 직접 결정 (대량 import 중에는 refresh 지연, 사용자 트래픽 중에는 즉시 refresh).

### Negative

- **분산 검색은 직접 구현**: 서버 1, 2 에 각각 인덱스를 두고 rsync (`backend/scripts/sync-index.sh`) 또는 CDC 컨슈머로 동기화. ES 의 자동 shard·replica 가 없음.
- **GUI 부재**: Kibana 같은 시각화 도구가 없어 인덱스 통계는 직접 API/메트릭으로 확인해야 함.
- **스키마 변경**: 필드 추가/삭제 시 전체 재색인 필요 (12 M 건 ≈ 69 분, 12 코어 + INDEX_THREADS=8 + heap 8 GB 기준).

### Neutral

- 인덱스 백업 = FS 백업. rsync 로 충분하지만 ES snapshot 같은 표준 도구는 없음.

## Validation

이 결정이 유효한지 확인하는 지표:

- **메모리 헤드룸**: 서버 1 에서 `free -h` 기준 used 가 9~10 GB 선에서 유지 (현재 충족)
- **검색 P95 < 3 s**: 100 VU k6 부하 테스트에서 검색 P95 2.61 s — 충족 ([BENCHMARK_REPORT.md](../BENCHMARK_REPORT.md))
- **재색인 시간 < 2 h**: 12 M 건 69 분 — 충족
- **인덱스 크기 < 50 GB**: 현재 39 GB — 충족

만약 데이터가 **50 M 건**을 넘어가거나 **분산 검색이 필요한 도메인 요구**(예: cross-region replica) 가 생기면 이 결정을 재검토 (`Supersedes #0001` 로 새 ADR 작성).

## References

- 구현: `backend/src/main/java/com/wiki/engine/post/internal/lucene/`
  - `LuceneIndexService.java` — IndexWriter, NRT refresh
  - `LuceneSearchService.java` — IndexSearcher, DisMax 쿼리
- 인덱스 동기화 스크립트: `backend/scripts/sync-index.sh` (rsync)
- Lucene 10.3.2 [공식 문서](https://lucene.apache.org/core/10_3_2/)
- Nori 형태소 분석기: [lucene-analysis-nori](https://lucene.apache.org/core/10_3_2/analysis/nori/)
- Wikipedia CirrusSearch 가 Lucene 을 직접 쓰는 방식 (참고): [CirrusSearch architecture](https://www.mediawiki.org/wiki/Extension:CirrusSearch)
