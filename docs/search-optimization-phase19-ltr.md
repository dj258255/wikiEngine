# Learning to Rank (LTR) + 카테고리 자동 분류

## 이전 단계 요약

18단계(쿼리 확장 + Query Understanding)에서 검색 품질의 Recall과 Precision을 개선했다.

| 지표 | 18단계 결과 |
|------|----------|
| 동의어 확장 | DB 기반 QueryExpansion, "AI" → "AI OR 인공지능" |
| 오타 교정 | DirectSpellChecker, "컴퓨텨" → "컴퓨터" |
| 복합어 보존 | Nori 사용자 사전 (userdict_ko.txt) |
| 재색인 인프라 | Directory Swap + SearcherManager 재생성 (무중단) |

검색 쿼리가 올바르게 해석되고 확장되지만, **랭킹 모델이 여전히 수동 규칙(BM25 + 고정 부스팅)에 의존**한다.

---

## 1단계: 정상 상태 인식

### 현재 랭킹 모델

```
BM25(title:3, content:1)
  + FeatureField("viewCount") 부스팅
  + FeatureField("likeCount") 부스팅
  + Recency Decay (30일 반감기, DoubleValuesSource)
```

Phase 7에서 구현한 이 랭킹은 **수동 가중치** 기반이다:
- title 가중치 3:1은 경험적 판단
- viewCount/likeCount 부스팅은 고정 로그 함수
- Recency 30일 반감기도 수동 설정

이 가중치들이 실제로 사용자가 원하는 결과 순서와 일치하는지 **데이터 기반으로 검증되지 않았다**.

---

## 2단계: 문제 상황 인식

### 문제 1: 수동 가중치의 한계

```
검색: "자바"

수동 랭킹 결과:
  1. 자바 (프로그래밍 언어)     ← BM25 title 매칭 + viewCount 높음
  2. 자바 (지명)               ← BM25 title 매칭 + viewCount 높음
  3. 자바스크립트               ← BM25 title 부분 매칭

사용자가 실제로 클릭한 순서 (검색 로그 기반):
  1. 자바 (프로그래밍 언어)     ← 80% 클릭
  2. 자바스크립트               ← 15% 클릭
  3. 자바 (지명)               ← 5% 클릭
```

수동 랭킹에서는 viewCount 기반으로 "자바 (지명)"이 2위이지만, 실제 사용자는 "자바스크립트"를 더 많이 클릭한다. **사용자 행동 데이터(클릭, 체류시간)를 랭킹에 반영하면 더 정확한 순서를 제공**할 수 있다.

### 문제 2: 새 게시글의 cold start

viewCount/likeCount가 0인 새 게시글은 랭킹에서 불리하다. 카테고리, 작성자, 제목 길이 등 메타데이터 기반 피처를 추가하면 cold start를 완화할 수 있다.

### 문제 3: 카테고리 자동 분류 미비

현재 카테고리는 위키 데이터 임포트 시 할당되었다. 사용자가 직접 게시글을 작성할 때 **카테고리를 수동으로 선택해야** 한다. 게시글 내용을 기반으로 자동 분류하면 UX가 개선된다.

---

## 3단계: 문제 분석

### Learning to Rank (LTR) 개요

LTR은 검색 랭킹을 **ML 모델로 학습**하는 기법이다. 사용자 행동 데이터(클릭, 체류시간, 이탈율)를 레이블로, 문서/쿼리 피처를 입력으로 사용하여 최적의 랭킹 함수를 학습한다.

```
학습 데이터:
  (쿼리, 문서, 피처 벡터, 관련도 레이블)
  ("자바", doc_123, [bm25=4.2, viewCount=50000, likeCount=300, ...], 5)
  ("자바", doc_456, [bm25=3.8, viewCount=30000, likeCount=100, ...], 3)

피처 예시:
  - BM25 스코어 (title, content 각각)
  - viewCount, likeCount (로그 변환)
  - 문서 길이 (짧은 문서 vs 긴 문서)
  - 문서 나이 (days since creation)
  - 카테고리 일치 여부
  - 쿼리-제목 편집 거리
  - 쿼리 term 커버리지 (쿼리 term 중 문서에 포함된 비율)
```

### LTR 접근 방식

| 방식 | 원리 | 대표 알고리즘 | 적합 상황 |
|------|------|-------------|---------|
| **Pointwise** | 개별 문서의 관련도 예측 | 선형 회귀, Random Forest | 단순, 데이터 적을 때 |
| **Pairwise** | 문서 쌍의 상대 순서 학습 | RankNet, LambdaMART | 클릭 로그 기반 |
| **Listwise** | 전체 순위 리스트 최적화 | ListNet, AdaRank | NDCG 직접 최적화 |

### Lucene LTR 지원 현황

Lucene은 `lucene-queries` 모듈에 `LTRScoringQuery`가 없지만, **Solr LTR 모듈** (`solr-ltr`)과 **Elasticsearch LTR 플러그인** (`elasticsearch-learning-to-rank`)이 존재한다.

순수 Lucene에서 LTR을 구현하려면:
1. **Rescoring 패턴**: BM25로 Top-N(100~1000) 추출 → ML 모델로 재랭킹
2. Lucene의 `Rescorer` API 활용

```
Phase 1 (BM25): 1,425만 건 → Top-1000 추출 (ms 단위)
Phase 2 (LTR):  Top-1000 → 피처 추출 → ML 모델 스코어링 → Top-10 반환
```

이 Two-Phase Ranking은 Google, Bing, 네이버 등 대부분의 검색엔진이 사용하는 패턴이다.

---

## 4단계: 대안 검토

### LTR 모델 선택

| 모델 | 장점 | 단점 | 판단 |
|------|------|------|------|
| **LambdaMART (XGBoost)** | NDCG 최적화, 검색 랭킹 업계 표준 | Python 모델 → Java 추론 변환 필요 | **선택** |
| **Linear Model** | 단순, 해석 가능, Java 네이티브 | 비선형 관계 학습 불가 | 초기 baseline |
| **Neural (BERT)** | 문맥 이해 가능 | 추론 지연 수백 ms, GPU 필요 | **탈락** (240ms SLA) |
| **Solr LTR Plugin** | 통합 환경 | Solr 마이그레이션 필요 | **탈락** |
| **Elasticsearch LTR** | ES 생태계 통합 | ES 별도 클러스터 필요 | **탈락** |

**선택**: Linear Model(baseline) → LambdaMART(XGBoost, ONNX 변환 후 Java 추론)

**ONNX Runtime ARM64 호환성**: OCI Free Tier는 ARM Ampere A1이다. ONNX Runtime Java 바인딩(`com.microsoft.onnxruntime:onnxruntime`)은 ARM64 Linux를 [공식 지원](https://github.com/microsoft/onnxruntime)한다. 다만 실제 배포 전 ARM 서버에서 추론 속도를 측정해야 한다.

**학습 데이터 cold start**: 현재 사용자 트래픽이 거의 없으므로 클릭 로그가 축적되지 않는다. 초기에는 **수동 레이블링**(검색어 50개 × 상위 20건 = 1,000쌍에 관련도 레이블)으로 소규모 학습 데이터를 구축하고, 트래픽이 쌓이면 클릭 로그 기반으로 전환한다.

### Lucene → Elasticsearch 트레이드오프 (설계 문서)

이 시점에서 Elasticsearch로 마이그레이션할지 검토한다.

| 관점 | Lucene (현재) | Elasticsearch |
|------|-------------|--------------|
| 운영 | 앱 임베디드, 별도 클러스터 없음 | 최소 3노드 클러스터 (JVM 각 2G+) |
| 비용 | Free Tier 내 | Free Tier 불가 (최소 6G RAM 추가) |
| LTR | Rescorer API 직접 구현 | `elasticsearch-learning-to-rank` 플러그인 |
| Facet | SortedSetDocValuesFacets (Phase 17) | Aggregation (더 풍부) |
| 동의어 | 직접 구현 (Phase 18) | SynonymGraphFilter 네이티브 |
| 분산 검색 | 앱 레벨 샤딩 (구현 필요) | 네이티브 샤딩 + 복제 |
| 학습 곡선 | Lucene API 직접 사용 | REST API, 높은 추상화 |

**결론**: 현재 인프라(Free Tier 2대)에서는 Elasticsearch 도입이 불가능하다. Lucene 임베디드로 LTR을 구현하고, 인프라가 확장되면 Elasticsearch 마이그레이션을 검토한다.

---

## 5단계: 구현 설계

### 5-1. 학습 데이터 수집

Phase 9에서 구현한 `search_logs` 테이블에 클릭 로그를 추가한다.

```sql
-- 기존 search_logs 확장
ALTER TABLE search_logs ADD COLUMN clicked_post_id BIGINT;
ALTER TABLE search_logs ADD COLUMN click_position INT;  -- 몇 번째 결과를 클릭했는지
ALTER TABLE search_logs ADD COLUMN dwell_time_ms BIGINT; -- 클릭 후 체류시간
```

```
관련도 레이블 생성 규칙:
  클릭 + 체류 30초 이상 → 관련도 5 (매우 관련)
  클릭 + 체류 10~30초  → 관련도 3 (관련)
  클릭 + 체류 10초 미만 → 관련도 1 (약간 관련, 또는 오클릭)
  미클릭               → 관련도 0 (무관)
```

### 5-2. 피처 추출

```java
public class LTRFeatureExtractor {

    /**
     * 쿼리-문서 쌍에서 LTR 피처 벡터를 추출한다.
     */
    public float[] extractFeatures(String query, Document doc, IndexSearcher searcher) {
        return new float[] {
            getBM25Score(query, doc, "title", searcher),   // title BM25
            getBM25Score(query, doc, "content", searcher), // content BM25
            log1p(getViewCount(doc)),                       // log(viewCount + 1)
            log1p(getLikeCount(doc)),                       // log(likeCount + 1)
            getDocAge(doc),                                 // 문서 나이 (일)
            getDocLength(doc),                              // 문서 길이 (토큰 수)
            getQueryCoverage(query, doc),                   // 쿼리 term 커버리지
            getEditDistance(query, getTitle(doc)),           // 쿼리-제목 편집 거리
        };
    }
}
```

### 5-3. Rescorer 기반 재랭킹

```java
public class LTRRescorer extends Rescorer {

    private final LTRModel model;  // 학습된 모델 (Linear or XGBoost ONNX)

    @Override
    public TopDocs rescore(IndexSearcher searcher, TopDocs firstPassTopDocs, int topN) {
        ScoreDoc[] hits = firstPassTopDocs.scoreDocs;
        float[] newScores = new float[hits.length];

        for (int i = 0; i < hits.length; i++) {
            Document doc = searcher.doc(hits[i].doc);
            float[] features = featureExtractor.extractFeatures(query, doc, searcher);
            newScores[i] = model.predict(features);
        }

        // 새 스코어로 정렬
        // ...
        return rerankedTopDocs;
    }
}
```

### 5-4. 카테고리 자동 분류

게시글 내용을 기반으로 카테고리를 자동 추천한다.

```
접근 방식:
  1. TF-IDF + Naive Bayes (baseline)
     - 기존 카테고리별 게시글로 학습
     - 새 게시글의 제목+본문 → 카테고리 예측
     - Java 네이티브 구현 가능 (Lucene의 ClassificationCategory)

  2. Lucene MoreLikeThis
     - 새 게시글과 유사한 기존 게시글을 찾아, 그 카테고리를 추천
     - 별도 모델 학습 불필요

  3. LLM 기반 분류 (Phase 21과 연계)
     - 고비용, 지연 시간 문제
```

**선택**: Lucene MoreLikeThis로 시작 (별도 학습 불필요) → TF-IDF + Naive Bayes로 정확도 개선.

```java
public List<String> suggestCategories(String title, String content) {
    MoreLikeThis mlt = new MoreLikeThis(indexReader);
    mlt.setFieldNames(new String[]{"title", "content"});
    mlt.setMinTermFreq(2);
    mlt.setMinDocFreq(5);

    Query query = mlt.like(new StringReader(title + " " + content), "content");
    TopDocs topDocs = searcher.search(query, 10);

    // 상위 10개 유사 문서의 카테고리를 집계하여 가장 빈도 높은 것 추천
    return aggregateCategories(topDocs);
}
```

---

## 작업 항목

### Part 1: 카테고리 자동 분류 (MoreLikeThis)
- [x] 주제별 카테고리 28개 정의 (컴퓨터 과학, 수학, 물리학 등 + 뉴스, 웹 콘텐츠)
- [x] 카테고리 키워드 기반 배치 분류 서비스 구현 (CategoryClassificationService)
- [x] MoreLikeThis 카테고리 추천 서비스 구현 (CategoryRecommendService)
- [x] 1,215만 건 category_id 재매핑 (DB UPDATE) — 2026-03-25 완료
- [x] Lucene 인덱스 갱신 (전체 재색인 완료)
- [ ] 📸 Before: categories 테이블 (namespace 기반 ~17개, "일반 문서" 97%) — **놓침 (이미 재분류됨)**
- [ ] 📸 After: categories 테이블 (주제별 28개, 분포 분산)
- [ ] 📸 카테고리별 게시글 수 분포 (DB GROUP BY — 막대 그래프)
- [ ] 📸 분류 정확도 샘플 검증 (카테고리당 10건 수동 확인)

### Part 2: Facet 집계 (Phase 17에서 이관) + 태그 인덱싱
- [x] `lucene-facet:10.3.2` 의존성 추가
- [x] `SortedSetDocValuesFacetField("category", categoryName)` 인덱스 추가
- [x] `FacetsConfig` + `config.build(doc)` 적용
- [x] `SortedSetDocValuesFacetCounts` 기반 Facet API 구현 (FacetsCollectorManager)
- [x] 검색 API 응답에 `categoryFacets: Map<String, Long>` 필드 추가
- [ ] 📸 검색 API 응답에 Facet 포함 확인 (재색인 후)
- [ ] 📸 Grafana: Facet 집계 추가 시 응답시간 영향

**태그 인덱싱 (재색인 시 함께):**
- [x] `toDocument()`에 tags 필드 추가 — post_tags 테이블에서 배치 단위 태그 프리로딩 (N+1 방지)
- [x] `TextField("tags", "tag1 tag2 ...", Field.Store.YES)` — 검색 대상
- [x] `SortedSetDocValuesFacetField("tag", tagName)` — 태그별 Facet 집계용 (다중 값, FacetsConfig.setMultiValued)
- [ ] 태그 Facet API 구현 — 카테고리 Facet과 동일 패턴 (추후)
- [ ] 검색 API에 `tag` 필터 파라미터 추가 (추후)
- [ ] 📸 검색 결과에 태그 Facet 표시 (추후)
- [ ] 📸 태그 클릭 → 해당 태그로 필터링 (추후)

> **카테고리 vs 태그 역할 분담:**
> - 카테고리 = 대분류 (28개, 1:1) → "컴퓨터 과학"
> - 태그 = 세분류 (다수, 1:N) → "프로그래밍 언어", "객체지향", "JVM"
> - DB에 이미 tags + post_tags 테이블 존재 (위키 [[분류:XXX]]에서 추출됨)
> - 재색인 때 Lucene 인덱스에 태그 필드를 추가하면 끝

**태그 API + DTO 연동 (재색인과 별개, 코드 변경):**
- [ ] Post 엔티티에서 태그 조회 로직 추가 (PostService에서 post_tags JOIN or 별도 쿼리)
- [ ] `PostDetailResponse`에 `List<String> tags` 필드 추가
- [ ] `PostSummaryResponse`에 `List<String> tags` 필드 추가
- [ ] `PostSearchResponse`에 `List<String> tags` 필드 추가
- [ ] 프론트엔드: 게시글 상세 페이지에 태그 칩 표시
- [ ] 프론트엔드: 검색 결과 각 항목에 태그 표시
- [ ] 프론트엔드: 태그 클릭 → 해당 태그로 검색 or 필터링
- [ ] 📸 게시글 상세: 태그 칩 UI (예: [프로그래밍 언어] [객체지향] [JVM])
- [ ] 📸 검색 결과: 각 결과 하단에 태그 표시

### Part 3: LTR (학습 데이터 + 모델)
- [ ] search_logs 테이블에 클릭 로그 컬럼 추가
- [ ] 프론트엔드에서 검색 결과 클릭 시 로그 전송 API 구현
- [ ] 수동 레이블링: 검색어 50개 × 상위 20건 = 1,000쌍 (cold start 대응)
- [ ] 피처 추출기 구현 (`LTRFeatureExtractor`)
- [ ] Linear Model baseline 구현 + 오프라인 평가 (NDCG@10)
- [ ] LambdaMART (XGBoost) 학습 → ONNX 변환 → Java 추론 (ARM64 검증)
- [ ] `LTRRescorer` 구현 (Lucene Rescorer API)
- [ ] 📸 Before: "자바" 검색 → BM25 기본 랭킹 (viewCount 기반)
- [ ] 📸 After: "자바" 검색 → LTR 재랭킹 (클릭 패턴 반영)
- [ ] 📸 NDCG@10 비교: BM25 only vs BM25 + Linear vs BM25 + LambdaMART
- [ ] 📸 Grafana: Rescore 추가 시 응답시간 영향 (P95 비교)

### 설계 문서
- [ ] Lucene → Elasticsearch 마이그레이션 트레이드오프 정리

---

## 부하 테스트 (Before/After)

| 지표 | Before (BM25 only) | After (BM25 + LTR) | 비고 |
|------|--------------------|--------------------|------|
| 평균 응답시간 | ???ms | ???ms | Rescore 추가 비용 |
| NDCG@10 | ???% | ???% | 랭킹 품질 |
| P@10 | ???% | ???% | 상위 10개 정확도 |
| 사용자 클릭율 | ???% | ???% | A/B 테스트 |
