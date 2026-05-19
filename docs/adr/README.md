# Architecture Decision Records (ADR)

이 디렉토리는 wikiEngine 의 **주요 기술 결정**을 그 *맥락(Context)*, *대안(Options)*, *결과(Consequences)* 와 함께 기록합니다.

> **왜 ADR인가?**
> 코드를 읽으면 *어떻게 동작하는가*는 알 수 있지만, *왜 그렇게 결정했는가*는 알 수 없습니다. ADR 은 그 간극을 메우는 일종의 결정 로그입니다. Michael Nygard 가 2011 년에 제안 ([원문](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions))한 형식을 [MADR](https://adr.github.io/madr/) 변형으로 따릅니다.

---

## 인덱스

| #    | 제목 | 상태 | 영향 영역 |
|------|------|------|----------|
| [0001](./0001-lucene-direct-vs-elasticsearch.md) | Lucene 직접 임베드 (Elasticsearch 미사용) | Accepted | 검색 · 인프라 |
| [0002](./0002-autocomplete-cqrs-mapreduce.md) | 자동완성 — CQRS + MapReduce + Redis Flat KV | Accepted | 검색 · 캐시 |
| [0003](./0003-xgboost4j-native-binding.md) | XGBoost4J 네이티브 바인딩 (ONNX 우회) | Accepted | 머신러닝 · LTR |
| [0004](./0004-disjunction-max-query-cjk.md) | 검색 쿼리 — DisjunctionMaxQuery(형태소 + n-gram) | Accepted | 검색 |
| [0005](./0005-index-sync-modulith-cdc.md) | 인덱스 동기화 — Modulith 이벤트 + Debezium CDC 이중화 | Accepted | 검색 · 메시징 |
| [0006](./0006-tiered-cache-consistent-hashing.md) | 2단 캐시 (Caffeine + Redis) + Consistent Hashing 3-Shard | Accepted | 캐시 |
| [0007](./0007-nginx-rw-split-mysql-replication.md) | Nginx R/W Split + MySQL Replication | Accepted | 인프라 · DB |

상태 정의: `Proposed` (제안) · `Accepted` (수용) · `Deprecated` (폐기) · `Superseded` (대체됨)

---

## 작성 규칙

1. **새 ADR**: [`template.md`](./template.md) 복사 후 `NNNN-kebab-case-title.md` 로 저장. 번호는 +1 증가.
2. **수정 금지**: 기존 ADR 의 결정 내용을 직접 수정하지 않습니다 — 결정이 바뀌면 새 번호로 작성하고 *Supersedes #NNNN* 를 명시합니다.
3. **언어**: 본문은 한국어, 파일명은 영어 kebab-case (검색성).
4. **분량**: 1 ADR ≈ 300~500 줄. 더 길어지면 별도 설계 문서로 분리.

---

## 참고 자료

- [Michael Nygard — Documenting Architecture Decisions (2011)](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
- [MADR — Markdown Any Decision Records](https://adr.github.io/madr/)
- [adr.github.io](https://adr.github.io/) — ADR 메타 사이트
- [joelparkerhenderson/architecture-decision-record](https://github.com/joelparkerhenderson/architecture-decision-record) — ADR 템플릿 모음
