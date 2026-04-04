package com.wiki.engine.post.internal.lucene;

import com.wiki.engine.post.Post;
import com.wiki.engine.post.internal.PostRepository;
import com.wiki.engine.post.internal.search.QueryExpansionService;
import com.wiki.engine.post.internal.search.RecencyDecaySource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.function.FunctionScoreQuery;
import org.apache.lucene.search.*;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lucene 검색 서비스.
 *
 * SearcherManager의 acquire/release 패턴으로 reader를 안전하게 관리한다.
 * 검색 중에 인덱스가 갱신되어도 진행 중인 검색은 이전 스냅샷으로 완료된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LuceneSearchService {

    private final SearcherManager searcherManager;
    private final Analyzer analyzer;
    private final PostRepository postRepository;
    private final QueryExpansionService queryExpansionService;
    private final FacetsConfig facetsConfig;
    private final LTRRescorer ltrRescorer;
    private final LTRFeatureExtractor ltrFeatureExtractor;

    /**
     * SortedSetDocValuesReaderState 캐싱 — SearcherManager.RefreshListener로 reader 갱신 시 재생성.
     *
     * <p>DefaultSortedSetDocValuesReaderState 구축 비용이 높으므로(per-segment ordinal map 계산),
     * 검색 경로에서 생성하지 않고 RefreshListener.afterRefresh()에서 미리 생성하여 volatile에 저장.
     * 검색 경로에서는 lock 없이 volatile read만 수행 → lock contention 제거.
     *
     * <p>현업 근거: Lucene 공식 Javadoc — "create it once and re-use for a given IndexReader".
     * Mike McCandless LUCENE-7905: OrdinalMap 빌드 비용 26.6M terms에 ~106초.
     */
    private volatile SortedSetDocValuesReaderState cachedFacetState;

    /**
     * SearcherManager에 RefreshListener를 등록하여,
     * IndexReader가 갱신될 때 FacetState를 미리 재생성한다.
     */
    @jakarta.annotation.PostConstruct
    void registerFacetStateRefreshListener() {
        searcherManager.addListener(new ReferenceManager.RefreshListener() {
            @Override
            public void beforeRefresh() {}

            @Override
            public void afterRefresh(boolean didRefresh) {
                if (didRefresh) {
                    try {
                        IndexSearcher searcher = searcherManager.acquire();
                        try {
                            cachedFacetState = new DefaultSortedSetDocValuesReaderState(
                                    searcher.getIndexReader(), facetsConfig);
                        } finally {
                            searcherManager.release(searcher);
                        }
                    } catch (Exception e) {
                        log.debug("FacetState 사전 빌드 실패 (재색인 전일 수 있음): {}", e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * 검색 결과 + snippet + Facet 정보를 함께 담는 record.
     * postId → highlightedSnippet 매핑으로 UnifiedHighlighter 결과를 전달.
     * categoryFacets: 카테고리명 → 매칭 건수 (전체 매칭 문서 기준, 페이징 무관).
     */
    public record SearchResult(Slice<Post> posts, Map<Long, String> snippets,
                                Map<String, Long> categoryFacets) {}

    /**
     * 키워드 검색 — Slice + snippet 반환.
     * title과 content 필드를 동시에 검색하며, title에 더 높은 가중치를 부여한다.
     * UnifiedHighlighter로 snippetSource 필드에서 검색어 주변 맥락을 추출한다.
     *
     * @param categoryId null이면 전체 검색, 값이 있으면 해당 카테고리만 필터링.
     */
    public SearchResult search(String keyword, Long categoryId, Pageable pageable) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query = buildQuery(keyword, categoryId);
            int offset = (int) pageable.getOffset();
            int limit = pageable.getPageSize();

            // LTR: rescore 활성화 시 더 많은 후보를 가져와서 재랭킹
            int fetchSize = ltrRescorer.isEnabled() && ltrRescorer.isModelLoaded()
                    ? Math.max(ltrRescorer.getRescoreWindow(), offset + limit + 1)
                    : offset + limit + 1;

            // TopDocs + FacetsCollector를 단일 패스로 수집 (이전: searcher.search 2회 → 1회)
            // MultiCollectorManager로 동일 쿼리의 이중 검색을 제거하여 I/O 절감.
            var topDocsManager = new TopScoreDocCollectorManager(fetchSize, null, fetchSize);
            var facetsManager = new FacetsCollectorManager();
            var multiResult = searcher.search(query, new MultiCollectorManager(topDocsManager, facetsManager));
            TopDocs topDocs = (TopDocs) multiResult[0];
            FacetsCollector facetsCollector = (FacetsCollector) multiResult[1];

            // LTR: BM25 Top-N → LTR Rescore → Top-K
            ScoreDoc[] finalDocs;
            if (ltrRescorer.isEnabled() && ltrRescorer.isModelLoaded()) {
                ScoreDoc[] rescored = ltrRescorer.rescore(
                        searcher, topDocs, keyword, ltrFeatureExtractor, offset + limit + 1);
                finalDocs = rescored;
            } else {
                finalDocs = topDocs.scoreDocs;
            }

            // 카테고리 Facet 집계 (전체 매칭 문서 대상, 페이징 무관)
            Map<String, Long> categoryFacets = collectCategoryFacets(searcher, facetsCollector);

            // offset 이후의 결과에서 ID + snippet 추출 (limit개까지만)
            StoredFields storedFields = searcher.storedFields();
            List<Long> postIds = new ArrayList<>();
            Map<Long, String> snippetMap = new HashMap<>();

            // UnifiedHighlighter로 검색어 주변 snippet 추출 (원본 topDocs 기반)
            Map<Integer, String> highlightedSnippets = extractHighlightedSnippets(
                    searcher, query, topDocs, offset, limit);

            for (int i = offset; i < Math.min(finalDocs.length, offset + limit); i++) {
                Document doc = storedFields.document(finalDocs[i].doc);
                long postId = Long.parseLong(doc.get("id"));
                postIds.add(postId);

                // LTR 재랭킹 시 highlightedSnippets 매핑이 달라질 수 있으므로
                // stored snippetSource에서 직접 추출
                String snippetSource = doc.get("snippetSource");
                if (snippetSource != null && !snippetSource.isBlank()) {
                    snippetMap.put(postId, snippetSource);
                }
            }

            if (postIds.isEmpty()) {
                return new SearchResult(new SliceImpl<>(List.of(), pageable, false), Map.of(), Map.of());
            }

            List<Post> posts = postRepository.findAllById(postIds);
            posts.sort((a, b) -> postIds.indexOf(a.getId()) - postIds.indexOf(b.getId()));

            boolean hasNext = finalDocs.length > offset + limit;
            return new SearchResult(new SliceImpl<>(posts, pageable, hasNext), snippetMap, categoryFacets);

        } catch (ParseException e) {
            log.warn("검색어 파싱 실패: keyword={}, error={}", keyword, e.getMessage());
            return new SearchResult(new SliceImpl<>(List.of(), pageable, false), Map.of(), Map.of());
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * UnifiedHighlighter로 snippetSource 필드에서 검색어 주변 텍스트를 추출한다.
     * snippetSource가 없는 문서(재색인 전)는 null을 반환하여 PostSearchResponse fallback으로 처리.
     */
    private Map<Integer, String> extractHighlightedSnippets(
            IndexSearcher searcher, Query query, TopDocs topDocs, int offset, int limit) {
        Map<Integer, String> result = new HashMap<>();
        try {
            UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                    .withMaxLength(10_000)  // Lucene 기본값 — snippetSource가 clean text라 성능 문제 없음
                    .build();

            // snippetSource 필드에서 하이라이트 추출
            String[] highlights = highlighter.highlight("snippetSource", query, topDocs, 1);

            for (int i = offset; i < Math.min(highlights.length, offset + limit); i++) {
                if (highlights[i] != null && !highlights[i].isBlank()) {
                    result.put(i, highlights[i]);
                }
            }
        } catch (Exception e) {
            // Highlighter 실패 시 (재색인 전 등) 무시 — fallback으로 앞 150자 사용
            log.debug("UnifiedHighlighter failed, falling back to truncation: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 자동완성: title_raw 필드에서 prefix 매칭으로 상위 10건 반환.
     *
     * title_raw = StringField(untokenized, lowercased) — Nori 분석 없이 원본 제목 그대로.
     * "성매" → PrefixQuery → "성매매" 정확 매칭. 한국어/영어 모두 동작.
     *
     * Nori-analyzed title 필드를 쓰면 "성매" → "성"으로 분해되어
     * "성악가", "남한산성" 등 무관한 결과가 나오는 문제 해결.
     *
     * 이 경로는 Redis 자동완성(메인)의 fallback으로만 사용.
     * Redis: 검색 로그 기반 인기 검색어 제안 (CQRS 읽기 경로)
     * Lucene: Redis 미스 시 문서 제목 기반 보조 제안
     */
    public List<String> autocomplete(String prefix, int limit) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            String normalized = prefix.toLowerCase().trim();
            if (normalized.isEmpty()) {
                return List.of();
            }

            Query query;
            if (normalized.contains(" ")) {
                // 띄어쓰기 포함 ("자바 가비지") → BM25 title AND 검색
                // PrefixQuery는 전체 문자열이 제목 시작과 일치해야 하므로 다중 단어 불가
                try {
                    var parser = new org.apache.lucene.queryparser.classic.MultiFieldQueryParser(
                            new String[]{"title"}, analyzer);
                    parser.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.AND);
                    query = parser.parse(
                            org.apache.lucene.queryparser.classic.QueryParser.escape(normalized));
                } catch (org.apache.lucene.queryparser.classic.ParseException e) {
                    return List.of();
                }
            } else {
                // 단일 단어/자모 ("자바", "자ㅂ") → title_jamo PrefixQuery (네이버/구글 패턴)
                String decomposed = com.wiki.engine.post.internal.autocomplete.JamoDecomposer.decompose(normalized);
                query = new PrefixQuery(new Term("title_jamo", decomposed));
            }

            TopDocs topDocs = searcher.search(query, limit);

            StoredFields storedFields = searcher.storedFields();
            List<String> titles = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = storedFields.document(scoreDoc.doc);
                titles.add(doc.get("title"));
            }
            return titles;
        } finally {
            searcherManager.release(searcher);
        }
    }

    private String analyzeFirstToken(String text) throws IOException {
        try (var stream = analyzer.tokenStream("title", text)) {
            var term = stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            stream.reset();
            if (stream.incrementToken()) {
                String result = term.toString();
                stream.end();
                return result;
            }
            stream.end();
        }
        return "";
    }

    /**
     * 검색 품질 평가용 검색.
     * bm25Only=true면 텍스트 관련성만, false면 인기도+최신성 부스트를 포함한 전체 랭킹.
     * Lucene stored fields에서 직접 읽어 DB 조회 없이 빠르게 결과를 반환한다.
     */
    public List<EvalDoc> searchForEval(String keyword, int topN, boolean bm25Only) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query;
            if (bm25Only) {
                var boosts = Map.of("title", 3.0f, "content", 1.0f);
                var parser = new MultiFieldQueryParser(new String[]{"title", "content"}, analyzer, boosts);
                parser.setPhraseSlop(2);
                query = parser.parse(escapePreservingPhrases(keyword));
            } else {
                query = buildQuery(keyword, null);
            }

            TopDocs topDocs = searcher.search(query, topN);
            StoredFields storedFields = searcher.storedFields();
            List<EvalDoc> results = new ArrayList<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = storedFields.document(scoreDoc.doc);
                results.add(new EvalDoc(
                        Long.parseLong(doc.get("id")),
                        doc.get("title"),
                        scoreDoc.score,
                        Long.parseLong(doc.get("viewCount"))
                ));
            }
            return results;
        } catch (ParseException e) {
            log.warn("평가 검색 파싱 실패: keyword={}", keyword, e);
            return List.of();
        } finally {
            searcherManager.release(searcher);
        }
    }

    public record EvalDoc(long id, String title, float score, long viewCount) {}

    /**
     * LTR 학습 데이터 추출 — 주어진 키워드에 대해 BM25 Top-N 문서의 피처를 추출한다.
     *
     * @param keyword 검색어
     * @param topN    추출할 문서 수 (기본 20)
     * @return 문서별 (postId, title, snippet, features) 목록
     */
    public List<LTRDocFeatures> extractLTRFeatures(String keyword, int topN,
                                                    LTRFeatureExtractor featureExtractor) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query = buildQuery(keyword, null);
            TopDocs topDocs = searcher.search(query, topN);
            StoredFields storedFields = searcher.storedFields();

            List<LTRDocFeatures> results = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = storedFields.document(sd.doc);
                long postId = Long.parseLong(doc.get("id"));
                String title = doc.get("title");
                String snippet = doc.get("snippetSource");

                float[] features = featureExtractor.extractFeatures(searcher, sd.doc, keyword);
                results.add(new LTRDocFeatures(postId, title,
                        snippet != null ? snippet.substring(0, Math.min(snippet.length(), 300)) : "",
                        features, sd.score));
            }
            return results;
        } catch (ParseException e) {
            log.warn("LTR 피처 추출 파싱 실패: keyword={}", keyword, e);
            return List.of();
        } finally {
            searcherManager.release(searcher);
        }
    }

    public record LTRDocFeatures(long postId, String title, String snippet,
                                  float[] features, float bm25Score) {}

    /**
     * BM25 텍스트 관련성 + 인기도(viewCount, likeCount) + 최신성(recency decay)을 결합한 쿼리.
     *
     * final_score = dis_max(BM25(title^3, content^1), ngram(title_ngram))  // MUST: 텍스트 OR n-gram
     *             + satu(viewCount, w=3.0, pivot=1000) // SHOULD: 조회수 부스트
     *             + satu(likeCount, w=2.0, pivot=100)  // SHOULD: 좋아요 부스트
     *             + recencyBoost(createdAt)             // SHOULD: 최신성 감쇠
     *
     * dis_max: 형태소 분석 쿼리와 n-gram 쿼리 중 높은 점수를 채택한다.
     * 형태소가 정상 동작하면 형태소 점수가 높고(boost=3.0),
     * 불완전 입력("안녕하세")으로 형태소가 실패하면 n-gram이 fallback 역할.
     * tie_breaker=0.3으로 양쪽 모두 매칭 시 소폭 보너스.
     * (Elastic 공식 CJK 검색 가이드 권장 패턴)
     */
    private Query buildQuery(String keyword, Long categoryId) throws ParseException {
        // 1. BM25 텍스트 관련성 쿼리 + 동의어 확장
        Query textQuery = buildTextQueryWithSynonyms(keyword);

        // 2. N-gram 쿼리 — 형태소 분석 우회, 문자 시퀀스 직접 매칭
        Query ngramQuery = buildNgramBoost(keyword);

        // 3. dis_max: 형태소 OR n-gram 중 높은 점수 채택
        // textQuery 내부에 title^3 boost가 있어 "하세" 완전 일치 점수가 극단적으로 높음.
        // n-gram에 2.0x boost를 줘서 "안녕하세요"의 5/5 n-gram 오버랩이
        // "하세"의 title 완전 일치와 경쟁할 수 있게 한다.
        Query textOrNgram = new DisjunctionMaxQuery(
                List.of(
                        textQuery,
                        new BoostQuery(ngramQuery, 2.0f)
                ),
                0.1f
        );

        // 4. 인기도 부스트 (FeatureField saturation — BlockMaxWAND 호환)
        Query viewBoost = FeatureField.newSaturationQuery("features", "viewCount", 3.0f, 1000);
        Query likeBoost = FeatureField.newSaturationQuery("features", "likeCount", 2.0f, 100);

        // 5. 최신성 감쇠 (exponential decay, 반감기 30일)
        Query recencyBoost = buildRecencyBoost(5.0f, 30);

        // 6. MUST(dis_max) + SHOULD(인기도 + 최신성) + FILTER(카테고리)
        BooleanQuery.Builder builder = new BooleanQuery.Builder()
                .add(textOrNgram, BooleanClause.Occur.MUST)
                .add(viewBoost, BooleanClause.Occur.SHOULD)
                .add(likeBoost, BooleanClause.Occur.SHOULD)
                .add(recencyBoost, BooleanClause.Occur.SHOULD);

        // 카테고리 필터 — FILTER는 스코어 미영향, bitset 캐싱 대상
        if (categoryId != null) {
            builder.add(LongField.newExactQuery("categoryId", categoryId), BooleanClause.Occur.FILTER);
        }

        // 블라인드 게시글 검색 제외
        builder.add(new TermQuery(new Term("blinded", "true")), BooleanClause.Occur.MUST_NOT);

        return builder.build();
    }

    /**
     * 동의어 확장이 적용된 텍스트 쿼리를 생성한다.
     *
     * 원래 키워드: "AI"
     * → 동의어 확장: ["AI" (boost=1.0), "인공지능" (boost=1.0)]
     * → BooleanQuery(SHOULD): title:"ai"^3 OR content:"ai" OR title:"인공지능"^3 OR content:"인공지능"
     *
     * 동의어가 없으면 기존 BM25 쿼리와 동일하게 동작한다.
     */
    private Query buildTextQueryWithSynonyms(String keyword) throws ParseException {
        var boosts = java.util.Map.of("title", 3.0f, "content", 1.0f);
        var parser = new MultiFieldQueryParser(new String[]{"title", "content"}, analyzer, boosts);
        parser.setPhraseSlop(2);

        // 원래 키워드를 토큰화하여 동의어 확장
        List<String> tokens = tokenize(keyword);

        // 토큰 전멸 폴백: Nori가 모든 토큰을 stop filter로 제거한 경우
        // (예: 미지의 품사 태깅 엣지 케이스)
        // title 필드의 term dictionary에서 prefix 매칭으로 결과를 반환한다.
        if (tokens.isEmpty()) {
            log.info("토큰 전멸 폴백 적용: keyword={}", keyword);
            return new PrefixQuery(new Term("title", keyword));
        }

        List<QueryExpansionService.ExpandedTerm> expanded = queryExpansionService.expand(tokens);

        // 동의어가 없으면 (원래 term만) 기존 방식
        boolean hasSynonyms = expanded.stream().anyMatch(e -> !e.original());
        if (!hasSynonyms) {
            return parser.parse(escapePreservingPhrases(keyword));
        }

        // 동의어가 있으면: 원래 쿼리 + 동의어 쿼리를 SHOULD로 묶기
        BooleanQuery.Builder synonymBuilder = new BooleanQuery.Builder();

        // 원래 키워드 쿼리 (boost=1.0)
        Query originalQuery = parser.parse(escapePreservingPhrases(keyword));
        synonymBuilder.add(new BoostQuery(originalQuery, 1.0f), BooleanClause.Occur.SHOULD);

        // 동의어별 쿼리 (각자 weight 적용)
        for (var term : expanded) {
            if (!term.original()) {
                try {
                    Query synQuery = parser.parse(escapePreservingPhrases(term.term()));
                    synonymBuilder.add(new BoostQuery(synQuery, (float) term.boost()), BooleanClause.Occur.SHOULD);
                } catch (ParseException e) {
                    log.debug("동의어 쿼리 파싱 실패: synonym={}", term.term());
                }
            }
        }

        // minimumNumberShouldMatch=1: 최소 1개(원래 or 동의어)는 매칭되어야 함
        synonymBuilder.setMinimumNumberShouldMatch(1);
        return synonymBuilder.build();
    }

    /**
     * 키워드를 Nori 형태소 분석기로 토큰화한다.
     * 동의어 확장 시 각 토큰별로 동의어를 찾기 위해 사용.
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        try (var stream = analyzer.tokenStream("title", text)) {
            var termAttr = stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(termAttr.toString());
            }
            stream.end();
        } catch (IOException e) {
            log.warn("토큰화 실패: text={}", text);
            tokens.add(text); // fallback: 원본 그대로
        }
        return tokens;
    }

    /**
     * N-gram 부스트 쿼리.
     * PerFieldAnalyzerWrapper의 title_ngram 분석기(2-3gram)로 키워드를 토큰화하여
     * title_ngram 필드에서 문자 시퀀스 매칭.
     *
     * "안녕하세" → ngrams: ["안녕","녕하","하세","안녕하","녕하세"]
     * "안녕하세요" 문서의 title_ngram: ["안녕","녕하","하세","세요","안녕하","녕하세","하세요"]
     * → 오버랩 5/5 (높은 점수)
     *
     * "하세" 문서의 title_ngram: ["하세"]
     * → 오버랩 1/5 (낮은 점수)
     */
    private Query buildNgramBoost(String keyword) {
        List<String> ngrams = new ArrayList<>();
        try (var stream = analyzer.tokenStream("title_ngram", keyword)) {
            var termAttr = stream.addAttribute(
                    org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                ngrams.add(termAttr.toString());
            }
            stream.end();
        } catch (IOException e) {
            log.warn("N-gram 토큰화 실패: keyword={}", keyword);
        }

        if (ngrams.isEmpty()) {
            return new MatchAllDocsQuery(); // 부스트 없음과 동일
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String ngram : ngrams) {
            builder.add(new TermQuery(new Term("title_ngram", ngram)),
                    BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    /**
     * Exponential decay 기반 최신성 부스트.
     * score = weight * exp(-ln2 / halfLifeDays * ageDays)
     * 반감기(halfLifeDays)가 지나면 가중치가 절반으로 감쇠.
     */
    private Query buildRecencyBoost(float weight, int halfLifeDays) {
        long nowMillis = Instant.now().toEpochMilli();
        double lambda = Math.log(2) / halfLifeDays;

        return new FunctionScoreQuery(new MatchAllDocsQuery(), new RecencyDecaySource(nowMillis, lambda, weight));
    }

    /**
     * 카테고리 Facet 집계.
     * SortedSetDocValuesFacetField("category")가 인덱스에 있으면 집계, 없으면 빈 맵 반환.
     * 재색인 전에는 Facet 필드가 없으므로 graceful fallback.
     */
    private Map<String, Long> collectCategoryFacets(IndexSearcher searcher, FacetsCollector fc) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            SortedSetDocValuesReaderState state = getOrCreateFacetState(searcher);
            Facets facets = new SortedSetDocValuesFacetCounts(state, fc);
            FacetResult facetResult = facets.getTopChildren(30, "category");
            if (facetResult != null) {
                for (LabelAndValue lv : facetResult.labelValues) {
                    result.put(lv.label, lv.value.longValue());
                }
            }
        } catch (Exception e) {
            // 재색인 전: SortedSetDocValues 필드 미존재 → 빈 맵 반환
            log.debug("카테고리 Facet 집계 실패 (재색인 전일 수 있음): {}", e.getMessage());
        }
        return result;
    }

    /**
     * 캐싱된 FacetState를 반환한다. RefreshListener가 reader 갱신 시 미리 빌드.
     * 아직 빌드 안 됐으면 (앱 기동 직후) 여기서 fallback 생성.
     */
    private SortedSetDocValuesReaderState getOrCreateFacetState(IndexSearcher searcher) throws IOException {
        SortedSetDocValuesReaderState state = cachedFacetState;
        if (state != null) {
            return state;
        }
        // fallback: RefreshListener가 아직 호출 안 된 경우 (앱 기동 직후)
        state = new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), facetsConfig);
        cachedFacetState = state;
        return state;
    }

    /**
     * 큰따옴표 구절은 보존하고, 나머지 부분만 특수문자를 이스케이프한다.
     * 예: '삼성전자 "반도체 기술" 투자' → '삼성전자 "반도체 기술" 투자'
     *     (삼성전자, 투자는 escape, "반도체 기술"은 그대로)
     */
    static String escapePreservingPhrases(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (input.charAt(i) == '"') {
                // 닫는 따옴표 찾기
                int close = input.indexOf('"', i + 1);
                if (close != -1) {
                    // 구절 전체를 그대로 보존 (따옴표 포함)
                    result.append(input, i, close + 1);
                    i = close + 1;
                } else {
                    // 닫는 따옴표 없음 — 일반 텍스트로 처리
                    result.append(MultiFieldQueryParser.escape(input.substring(i)));
                    break;
                }
            } else {
                // 다음 여는 따옴표까지의 일반 텍스트를 escape
                int nextQuote = input.indexOf('"', i);
                if (nextQuote != -1) {
                    result.append(MultiFieldQueryParser.escape(input.substring(i, nextQuote)));
                    i = nextQuote;
                } else {
                    result.append(MultiFieldQueryParser.escape(input.substring(i)));
                    break;
                }
            }
        }
        return result.toString();
    }
}
