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

            // limit + 1 조회하여 hasNext 판단
            TopDocs topDocs = searcher.search(query, offset + limit + 1);

            // Phase 19.2: 카테고리 Facet 집계 (전체 매칭 문서 대상, 페이징 무관)
            FacetsCollector facetsCollector = searcher.search(query, new FacetsCollectorManager());
            Map<String, Long> categoryFacets = collectCategoryFacets(searcher, facetsCollector);

            // offset 이후의 결과에서 ID + snippet 추출 (limit개까지만)
            StoredFields storedFields = searcher.storedFields();
            List<Long> postIds = new ArrayList<>();
            Map<Long, String> snippetMap = new HashMap<>();

            // UnifiedHighlighter로 검색어 주변 snippet 추출
            Map<Integer, String> highlightedSnippets = extractHighlightedSnippets(
                    searcher, query, topDocs, offset, limit);

            for (int i = offset; i < Math.min(topDocs.scoreDocs.length, offset + limit); i++) {
                Document doc = storedFields.document(topDocs.scoreDocs[i].doc);
                long postId = Long.parseLong(doc.get("id"));
                postIds.add(postId);

                // Highlighter snippet이 있으면 사용, 없으면 null (fallback은 PostSearchResponse에서)
                String highlighted = highlightedSnippets.get(i);
                if (highlighted != null && !highlighted.isBlank()) {
                    snippetMap.put(postId, highlighted);
                }
            }

            if (postIds.isEmpty()) {
                return new SearchResult(new SliceImpl<>(List.of(), pageable, false), Map.of(), Map.of());
            }

            List<Post> posts = postRepository.findAllById(postIds);
            posts.sort((a, b) -> postIds.indexOf(a.getId()) - postIds.indexOf(b.getId()));

            boolean hasNext = topDocs.scoreDocs.length > offset + limit;
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
                    .withMaxLength(500)
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
     * 자동완성: title 필드에서 prefix 매칭으로 상위 10건 반환.
     * Lucene PrefixQuery로 역색인에서 즉시 조회한다.
     */
    public List<String> autocomplete(String prefix, int limit) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            // Nori로 prefix를 분석하여 첫 번째 토큰으로 PrefixQuery 생성
            String analyzed = analyzeFirstToken(prefix);
            if (analyzed.isEmpty()) {
                return List.of();
            }

            Query query = new PrefixQuery(new Term("title", analyzed));
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
     * BM25 텍스트 관련성 + 인기도(viewCount, likeCount) + 최신성(recency decay)을 결합한 쿼리.
     *
     * final_score = BM25(title^3, content^1)          // MUST: 텍스트 관련성
     *             + satu(viewCount, w=3.0, pivot=1000) // SHOULD: 조회수 부스트
     *             + satu(likeCount, w=2.0, pivot=100)  // SHOULD: 좋아요 부스트
     *             + recencyBoost(createdAt)             // SHOULD: 최신성 감쇠
     */
    private Query buildQuery(String keyword, Long categoryId) throws ParseException {
        // 1. BM25 텍스트 관련성 쿼리 + 동의어 확장 (Phase 18)
        Query textQuery = buildTextQueryWithSynonyms(keyword);

        // 2. 인기도 부스트 (FeatureField saturation — BlockMaxWAND 호환)
        Query viewBoost = FeatureField.newSaturationQuery("features", "viewCount", 3.0f, 1000);
        Query likeBoost = FeatureField.newSaturationQuery("features", "likeCount", 2.0f, 100);

        // 3. 최신성 감쇠 (exponential decay, 반감기 30일)
        Query recencyBoost = buildRecencyBoost(5.0f, 30);

        // 4. MUST(텍스트) + SHOULD(인기도 + 최신성) + FILTER(카테고리)
        BooleanQuery.Builder builder = new BooleanQuery.Builder()
                .add(textQuery, BooleanClause.Occur.MUST)
                .add(viewBoost, BooleanClause.Occur.SHOULD)
                .add(likeBoost, BooleanClause.Occur.SHOULD)
                .add(recencyBoost, BooleanClause.Occur.SHOULD);

        // Phase 17: 카테고리 필터 — FILTER는 스코어 미영향, bitset 캐싱 대상
        if (categoryId != null) {
            builder.add(LongField.newExactQuery("categoryId", categoryId), BooleanClause.Occur.FILTER);
        }

        return builder.build();
    }

    /**
     * Phase 18: 동의어 확장이 적용된 텍스트 쿼리를 생성한다.
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
     * Phase 19.2: 카테고리 Facet 집계.
     * SortedSetDocValuesFacetField("category")가 인덱스에 있으면 집계, 없으면 빈 맵 반환.
     * 재색인 전에는 Facet 필드가 없으므로 graceful fallback.
     */
    private Map<String, Long> collectCategoryFacets(IndexSearcher searcher, FacetsCollector fc) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(
                    searcher.getIndexReader(), facetsConfig);
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
