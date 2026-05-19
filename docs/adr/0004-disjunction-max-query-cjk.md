# 0004. 검색 쿼리 — DisjunctionMaxQuery(형태소 + n-gram)

- **상태**: Accepted
- **결정일**: 2026-04-04
- **관련 ADR**: [#0001](./0001-lucene-direct-vs-elasticsearch.md)

## Context

한국어 검색의 고전적 딜레마: **형태소 분석기는 정확하지만 누락이 있고, n-gram 은 누락이 없지만 노이즈가 많다**.

구체적 사례:

- 검색어 "**자바**" — Nori 형태소 분석 시 단일 토큰. 본문에 "자바스크립트"가 있어도 형태소 단위가 달라 매칭 실패 (의도된 동작, 그러나 사용자는 매칭되길 원함)
- 검색어 "**ㅇㅇ**" 같은 자모 입력 — 형태소 분석기를 우회해야 함
- 검색어 "**unknown_proper_noun**" — Nori 사전에 없는 신조어/고유명사는 형태소 분석이 부정확

요구사항:

- 형태소 기반 정확도 유지 (한국어 자연어 의미 보존)
- 동시에 부분 매칭 안전망 (n-gram 으로 폴백)
- 한 필드의 점수가 과도하게 다른 필드를 압도하지 않게

## Decision Drivers

1. **검색 재현율(Recall) 향상** — 사용자가 검색 결과 0건을 경험하지 않도록
2. **점수 안정성** — 한 쿼리 분기가 다른 분기를 압도하지 않게
3. **운영 부담 최소** — 인덱스 필드 추가 비용이 너무 크면 안 됨

## Considered Options

### Option A: Nori 형태소 단일 쿼리

- 장점: 한국어 의미 정확, 인덱스 크기 작음
- 단점:
  - 자모/부분 prefix 검색 불가
  - 사전에 없는 신조어 검색 실패
  - **검색 결과 0건** 경험 빈도 높음

### Option B: n-gram 단독 (2-3gram)

- 장점: 누락 없음, 자모 검색도 1-2gram 으로 처리 가능
- 단점:
  - **점수 노이즈** — 부분 일치만으로도 점수가 높아져 의미적으로 무관한 문서가 상위 노출
  - 인덱스 크기 2-3배 증가
  - 한국어 의미 보존 부재 (조사·어미 무시)

### Option C: BooleanQuery(SHOULD, SHOULD) 단순 결합

- 장점: 구현 단순
- 단점:
  - **점수 합산** 방식이라 형태소 매칭 + n-gram 매칭이 모두 되는 문서가 압도적 점수 → 한쪽만 맞는 문서는 묻힘
  - tie-breaker 조정 불가

### Option D: DisjunctionMaxQuery(형태소, n-gram*2.0, tie_breaker=0.1) (선택)

Elasticsearch CJK 분석 가이드의 표준 패턴:

```
score(doc) = max(score_morpheme, score_ngram * 2.0)
           + 0.1 * (other_subscore)
```

- 장점:
  - **max 기반** → 두 분기 중 강한 쪽이 점수를 지배 (BooleanQuery 의 합산 문제 해결)
  - `tie_breaker=0.1` 로 다른 분기도 약하게 반영 (완전 무시 아님)
  - n-gram boost `*2.0` 로 안전망 가중치 조절 가능
- 단점:
  - `title_ngram` 별도 필드 인덱싱 필요 (인덱스 크기 +약 5 GB)
  - 가중치 튜닝 필요

## Decision

**Option D — DisjunctionMaxQuery(형태소, n-gram*2.0, tie_breaker=0.1)** 선택.

`title_ngram` 은 `PerFieldAnalyzerWrapper` 로 N-Gram (min=2, max=3) 분석기를 적용, 일반 `title` 필드는 Nori 그대로. 검색 시 두 필드를 DisMax 로 묶어:

```java
DisjunctionMaxQuery dmq = new DisjunctionMaxQuery(
    List.of(
        textQuery,                      // Nori 형태소
        new BoostQuery(ngramQuery, 2.0f) // n-gram 안전망
    ),
    0.1f  // tie_breaker
);
```

- `*2.0` boost 는 형태소 누락 시 n-gram 분기가 더 적극적으로 기여하도록 함
- `tie_breaker=0.1` 은 Elastic 의 권장값 (0.0 = 완전 max, 1.0 = 완전 합산)

## Consequences

### Positive

- **0건 결과 빈도 감소** — 자모·신조어·prefix 검색에서도 결과 반환
- **점수 노이즈 억제** — max 기반이라 무관한 부분 일치가 상위 점수 못 받음
- **CJK 표준 패턴 차용** — Elasticsearch 커뮤니티에서 검증된 구조

### Negative

- **인덱스 크기 증가** — `title_ngram` 으로 약 5 GB (전체 39 GB 의 12%)
- **가중치 튜닝 부담** — `*2.0` 과 `tie_breaker=0.1` 은 경험치, 데이터 변화 시 재조정 필요
- **NRT refresh 시간** — n-gram 인덱싱이 형태소보다 토큰 수가 많아 indexing 처리량 다소 감소

### Neutral

- 자동완성 fallback ([ADR-0002](./0002-autocomplete-cqrs-mapreduce.md))에서도 동일 인덱스의 `title_raw` PrefixQuery 활용 가능

## Validation

- **수동 회귀 케이스**: "자바" 검색 시 "자바스크립트" 문서가 등장 (이전: 미등장 → 현재: 등장)
- **0건 결과 비율** — 검색 로그에서 빈 결과 응답률 측정. 변경 전후 비교 (수치는 운영 로그 기준)
- **검색 P95** — 변경 후 P95 < 3 s 유지 (실측 2.61 s)
- **NDCG@10** — Gemini judge 기준 변경 후 측정 (LTR 학습 데이터 갱신 시 함께)

만약 P95 가 5 s 를 넘기 시작하거나 n-gram 인덱스가 인덱스 전체의 20% 를 초과하면 가중치/필드 구조 재검토.

## References

- 구현: `backend/src/main/java/com/wiki/engine/post/internal/lucene/LuceneSearchService.java`
  - `buildDisMaxQuery(...)` 메서드
- 필드 정의: `LuceneIndexService.java` — `title`, `title_ngram`, `title_raw`, `title_jamo`
- Analyzer 분기: `PerFieldAnalyzerWrapper` 사용
- Elastic CJK 가이드: [Elasticsearch — CJK Analysis](https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-cjk-analyzer.html)
- 관련 메모리: `project_search_improvements_20260404.md`
