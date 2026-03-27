# 콘텐츠 필터링 — 운영 안전장치

## 이전 단계 요약

19단계(LTR + 카테고리 자동 분류)에서 ML 기반 랭킹과 카테고리 추천을 구현했다.

| 지표 | 19단계 결과 |
|------|----------|
| LTR Rescore | BM25 Top-1000 → ML 재랭킹 |
| NDCG@10 | ???% → ???% 개선 |
| 카테고리 자동 분류 | MoreLikeThis 기반 추천 |

검색 기능이 고도화되었지만, 커뮤니티 서비스 운영에 필수적인 **콘텐츠 안전장치**가 없다.

---

## 1단계: 정상 상태 인식

### 현재 콘텐츠 관리 현황

현재 wikiEngine은 위키피디아 데이터 기반이므로, 콘텐츠 품질이 높고 유해 콘텐츠가 거의 없다. 하지만 사용자 게시글 작성이 가능해지면 다음 문제가 발생한다:

```
유해 콘텐츠 유형:
  1. 금칙어 포함 게시글 (욕설, 혐오 표현)
  2. 스팸 게시글 (광고, 도배)
  3. 저품질 콘텐츠 (의미 없는 내용, 낚시 제목)
  4. 개인정보 노출 (전화번호, 주소 등)
```

현재 이에 대한 **필터링/모니터링/신고 시스템이 전혀 없다**.

---

## 2단계: 문제 상황 인식

### 커뮤니티 서비스의 콘텐츠 관리 필요성

| 문제 | 영향 | 사례 |
|------|------|------|
| 금칙어 미차단 | 커뮤니티 분위기 악화, 사용자 이탈 | 대부분의 커뮤니티 서비스에서 기본 제공 |
| 스팸 미차단 | 검색 결과 오염, 사용자 경험 저하 | Stack Overflow, Reddit 등 모두 스팸 필터 운영 |
| 신고 시스템 부재 | 유해 콘텐츠 자정 기능 없음 | 카카오, 네이버 모두 신고/블라인드 시스템 운영 |
| 자동완성 오염 | 유해 검색어가 자동완성에 노출 | 구글/네이버 자동완성 필터링 |

---

## 3단계: 문제 분석

### 왜 지금 콘텐츠 필터링이 필요한가

현재 데이터는 위키피디아 덤프이므로 유해 콘텐츠가 거의 없다. 하지만:

1. **게시글 작성 API가 이미 열려 있다** — `POST /api/v1.0/posts`로 누구나(인증된 사용자) 게시글 작성 가능
2. **k6 부하 테스트에서 게시글을 대량 생성한다** — 제목/본문에 아무 문자열이나 들어감
3. **자동완성이 검색 로그 기반이다** — 유해 검색어가 자동완성에 그대로 노출될 수 있음

실제로 `curl -X POST /api/v1.0/posts -d '{"title":"금칙어 포함 제목","content":"..."}'`를 보내면 **아무 검증 없이 DB에 저장되고 Lucene 인덱스에 포함된다.** 이것이 구조적 문제다.

### Aho-Corasick 선택 근거

금칙어 탐지 알고리즘 비교:

| 방식 | 시간 복잡도 | 구현 | 판단 |
|------|----------|------|------|
| `String.contains()` 루프 | O(N×M) (N=텍스트, M=금칙어 수) | 단순 | 금칙어 1,000개면 느려짐 |
| **Aho-Corasick** | **O(N+Z)** (Z=매칭 수, M에 무관) | [robert-bor/aho-corasick](https://github.com/robert-bor/aho-corasick) 라이브러리 사용 | **선택** |
| 정규식 합성 | O(N) | 금칙어 변경 시 재컴파일 | 대안 |

---

## 4단계: 구현 설계

### 4-1. 금칙어 필터링

```
두 가지 시점에 적용:
  1. 쓰기 시점: 게시글 작성/수정 시 금칙어 포함 여부 검사
  2. 검색 시점: 자동완성 결과에서 금칙어 포함 제안 필터링
```

```java
@Service
public class ContentFilterService {

    private final Set<String> bannedWords;          // 정확 매칭
    private final AhoCorasickAutomaton bannedTrie;  // 부분 매칭 (다중 패턴)

    /**
     * Aho-Corasick 알고리즘으로 텍스트 내 금칙어를 O(N) 시간에 탐지한다.
     * N = 텍스트 길이. 금칙어 수에 무관하게 선형 시간.
     *
     * 단순 contains() 루프는 O(N×M) (M = 금칙어 수)으로 금칙어가 늘어나면 느려진다.
     */
    public FilterResult checkContent(String text) {
        List<String> found = bannedTrie.search(text);
        return new FilterResult(found.isEmpty(), found);
    }

    /**
     * 자동완성 결과에서 금칙어 포함 제안을 필터링한다.
     */
    public List<String> filterAutocompleteSuggestions(List<String> suggestions) {
        return suggestions.stream()
            .filter(s -> checkContent(s).isClean())
            .toList();
    }
}
```

#### 금칙어 사전 관리

```sql
CREATE TABLE banned_words (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    word VARCHAR(100) NOT NULL UNIQUE,
    category ENUM('PROFANITY', 'HATE_SPEECH', 'SPAM', 'ADULT', 'PERSONAL_INFO') NOT NULL,
    severity ENUM('LOW', 'MEDIUM', 'HIGH') NOT NULL DEFAULT 'MEDIUM',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_category (category)
);
```

- 금칙어 변경 시 Caffeine 캐시 갱신 (TTL 10분)
- Aho-Corasick automaton 재빌드

### 4-2. 스팸 탐지

```
규칙 기반 스팸 탐지 (Phase 20):
  1. URL 과다 포함 (본문 내 URL 3개 이상)
  2. 반복 게시 (같은 사용자가 1분 내 5건 이상)
  3. 중복 콘텐츠 (제목+본문 해시 중복)
  4. 특수문자 과다 (본문의 30% 이상이 특수문자)

ML 기반 스팸 탐지 (향후):
  5. TF-IDF + 분류기로 스팸 패턴 학습
```

```java
@Service
public class SpamDetectionService {

    public SpamCheckResult check(Post post, Long authorId) {
        List<String> reasons = new ArrayList<>();

        // 규칙 1: URL 과다
        long urlCount = countUrls(post.getContent());
        if (urlCount >= 3) reasons.add("URL_EXCESSIVE");

        // 규칙 2: 반복 게시
        long recentPostCount = postRepository.countByAuthorIdAndCreatedAtAfter(
            authorId, Instant.now().minusSeconds(60));
        if (recentPostCount >= 5) reasons.add("RAPID_POSTING");

        // 규칙 3: 중복 콘텐츠
        String contentHash = hashContent(post.getTitle(), post.getContent());
        if (postRepository.existsByContentHash(contentHash)) reasons.add("DUPLICATE");

        // 규칙 4: 특수문자 과다
        double specialCharRatio = getSpecialCharRatio(post.getContent());
        if (specialCharRatio > 0.3) reasons.add("SPECIAL_CHAR_EXCESSIVE");

        return new SpamCheckResult(reasons.isEmpty(), reasons);
    }
}
```

### 4-3. 신고 시스템

```sql
CREATE TABLE post_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    reason ENUM('SPAM', 'PROFANITY', 'HARASSMENT', 'MISINFORMATION', 'OTHER') NOT NULL,
    description VARCHAR(500),
    status ENUM('PENDING', 'REVIEWED', 'ACCEPTED', 'REJECTED') DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP,
    INDEX idx_post_id (post_id),
    INDEX idx_status (status),
    UNIQUE KEY uk_post_reporter (post_id, reporter_id)  -- 동일 게시글 중복 신고 방지
);
```

```
신고 처리 흐름:
  1. 사용자가 게시글 신고 (POST /api/v1.0/posts/{id}/report)
  2. 신고 누적 N건 이상 → 자동 블라인드 (검색 결과에서 제외)
  3. 관리자 리뷰 → 승인(삭제) 또는 반려(복원)
```

#### 블라인드 처리 — Lucene 인덱스 연동

```java
// 블라인드된 게시글은 검색에서 제외
// 방법 1: Lucene 인덱스에서 삭제 (CDC 이벤트로 자동)
// 방법 2: 검색 시 필터 추가 (Occur.MUST_NOT)

// 방법 2 선택 — 복원 가능하도록
Query blindFilter = new TermQuery(new Term("status", "BLIND"));
builder.add(blindFilter, BooleanClause.Occur.MUST_NOT);
```

### 4-4. 자동완성 안전장치

Phase 9에서 구현한 검색 로그 기반 자동완성에 금칙어 필터를 적용한다.

```
현재 자동완성 흐름 (Phase 15):
  사용자 입력 → Redis flat KV 조회 → Top-10 반환

필터 추가:
  사용자 입력 → Redis flat KV 조회 → 금칙어 필터링 → Top-10 반환
```

> 금칙어 필터는 Redis 조회 후 앱 레벨에서 수행한다. Redis에 금칙어를 저장하지 않고, Caffeine 캐시에 올린 금칙어 Set으로 필터링한다. 이유: Redis KV 재빌드 주기(1시간)와 금칙어 업데이트가 독립적이어야 하므로.

---

## 작업 항목 — 검색팀 영역

> **스코프 조정 (2026-03-27):** 게시글 작성 검증(금칙어 차단, 스팸 탐지), 신고 CRUD, 관리자 대시보드는 서비스팀 영역으로 제외.
> 검색팀은 **자동완성 금칙어 필터링 + 블라인드 게시글 검색 제외**에 집중한다.

### Part 1: 자동완성 금칙어 필터링 (검색 품질 관리)
- [x] banned_words 테이블 생성 (V2 Flyway 마이그레이션)
- [x] 금칙어 초기 데이터 적재 — **3,094개** (LDNOOBWV2 한국어 목록)
  - 출처: [LDNOOBWV2/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words_V2](https://github.com/LDNOOBWV2/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words_V2) `data/ko.txt`
  - 포함 내용: 비속어, 변형 (예: "10발" → "씨발" 우회), 혐오 표현
  - 리소스 파일: `banned_words_ko.txt` → 앱 시작 시 DB 자동 로딩 (BannedWordInitializer, 최초 1회)
- [x] ContentFilterService 구현 — 금칙어 HashSet + Caffeine 캐시 (TTL 10분)
- [x] 자동완성 결과에서 금칙어 포함 제안 필터링 (PostService.autocomplete → filterSuggestions)
- [ ] 📸 Before: 금칙어 포함 검색어가 자동완성에 노출
- [ ] 📸 After: 금칙어 포함 제안이 필터링되어 미노출

### Part 2: 블라인드 게시글 검색 제외 (검색 결과 품질)
- [x] Post 엔티티에 `blinded` 필드 추가 (default false)
- [x] Lucene 인덱싱 시 `blinded` 필드 포함 (KeywordField)
- [x] 검색 쿼리에 블라인드 필터 추가 (`Occur.MUST_NOT blinded=true`)
  - 재색인 불필요: 기존 인덱스에 `blinded` 필드 없음 → MUST_NOT 매칭 없음 → 전체 통과 (정상)
  - 블라인드 처리 시 CDC 이벤트 → 해당 문서만 재인덱싱 → 검색 제외 반영
- [x] 블라인드 처리 API (관리자용 PUT `/admin/lucene/posts/{id}/blind`)
- [ ] 📸 Before: 블라인드 게시글이 검색 결과에 노출
- [ ] 📸 After: 블라인드 처리 후 검색 결과에서 제외 확인

### Part 3: 자동완성 Lucene fallback 품질 개선

> **문제**: Lucene fallback에서 `PrefixQuery`가 Nori-analyzed `title` 필드를 사용.
> "성매" → Nori → "성"으로 분해 → "성악가", "남한산성" 등 무관한 결과 반환.
>
> **원인**: 자동완성은 형태소 분석 없이 원본 prefix로 매칭해야 하는데,
> 검색용 Nori-analyzed 필드를 공유하고 있었음.
>
> **해결**: `title_raw` StringField (untokenized, lowercased) 추가 + PrefixQuery 대상 변경.
> 한국어 "성매" → "성매매" ✅, 영어 "prog" → "programming" ✅

- [x] `title_raw` StringField 추가 (toDocument — untokenized, lowercased, Store.NO)
- [x] `autocomplete()` → `title_raw` 필드로 PrefixQuery 변경 (Nori 분석 제거)
- [x] 한국어 + 영어 동시 지원 (toLowerCase로 통일)
  - 재색인 필요: `title_raw` 필드 추가

> **아키텍처 정리:**
> - **Redis** (메인): 검색 로그 기반 인기 검색어 제안 — CQRS 읽기 경로, O(1)
> - **Lucene** (fallback): Redis 미스 시 문서 제목 기반 보조 제안 — `title_raw` PrefixQuery
> - 자동완성 ≠ 형태소 분석 (현업 표준: 네이버/구글/ES 모두 별도 untokenized 필드 사용)

### Part 4: Negative Caching — 빈 결과 짧은 TTL

> **문제**: 앱 기동 직후 인덱스 로딩 전에 검색 → 0건 캐시 → 5~10분간 0건 유지.
>
> **해결**: TieredCacheService에서 빈 결과는 30초 TTL, 정상 결과는 기존 10분 유지.
> Negative caching은 AWS ElastiCache, CDN, DNS(RFC 2308) 모두 사용하는 표준 패턴.

- [x] TieredCacheService: 빈 결과 판별 (`isEmpty()`) + 짧은 TTL(30초) 적용
- [x] Cache penetration 방지 (빈 결과도 캐시하되 짧은 TTL)

### 서비스팀 영역 (Phase 20 범위 외 — 추후 필요 시 구현)
- 게시글 작성/수정 시 금칙어 검사 (pre-moderation)
- SpamDetectionService (규칙 기반 4개: URL 과다, 반복 게시, 중복 콘텐츠, 특수문자 과다)
- post_reports 테이블 + 신고 API + 자동 블라인드 로직
- 관리자 리뷰 대시보드
- 프론트엔드 신고 UI
