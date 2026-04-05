package com.wiki.engine.post.internal.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.io.StringReader;
import java.time.Instant;
import java.util.*;

/**
 * LTR(Learning to Rank) 피처 추출기 — 14개 피처.
 *
 * <p>cold start 시 14개 피처로 시작. BM25 score가 최중요 피처.
 *
 * <p>피처 분류:
 * - query-dependent (6개): bm25Title, bm25Content, bm25Snippet, queryTermCoverageTitle,
 *   queryTermCoverageContent, exactTitleMatch, tagOverlap
 * - query-independent (5개): titleLength, contentLength, freshnessDays, viewCount, likeCount, categoryId
 * - query-level (1개): queryLength
 *
 * <p>출처:
 * - LETOR benchmark features (Microsoft Research)
 * - OpenSource Connections: "LTR 101 — Linear Models"
 * - Booking.com KDD 2019: "GBDT models are hard to beat for tabular features"
 */
@Component
public class LTRFeatureExtractor {

    public static final String[] FEATURE_NAMES = {
            "bm25Title", "bm25Content", "bm25Snippet",
            "queryTermCoverageTitle", "queryTermCoverageContent",
            "exactTitleMatch", "tagOverlap",
            "titleLength", "contentLength", "freshnessDays",
            "viewCount", "likeCount", "categoryId", "queryLength"
    };

    public static final int FEATURE_COUNT = FEATURE_NAMES.length;

    private final Analyzer analyzer;

    public LTRFeatureExtractor(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * 단일 문서에 대해 14개 피처를 추출한다.
     *
     * @param searcher Lucene IndexSearcher
     * @param docId    Lucene 내부 문서 ID (ScoreDoc.doc)
     * @param keyword  사용자 검색어 (원본)
     * @return 14개 피처 배열 (FEATURE_NAMES 순서)
     */
    public float[] extractFeatures(IndexSearcher searcher, int docId,
                                   String keyword) throws IOException {
        Document doc = searcher.storedFields().document(docId);
        List<String> queryTerms = tokenize(keyword);

        float[] features = new float[FEATURE_COUNT];

        // 1~3. BM25 scores (title, content, snippetSource)
        features[0] = computeBM25(searcher, docId, "title", keyword);
        features[1] = computeBM25(searcher, docId, "content", keyword);
        features[2] = computeBM25(searcher, docId, "snippetSource", keyword);

        // 4~5. Query term coverage
        String title = doc.get("title");
        features[3] = computeTermCoverage(queryTerms, title);
        features[4] = computeTermCoverage(queryTerms, doc.get("snippetSource"));

        // 6. Exact title match
        features[5] = (title != null && title.toLowerCase().contains(keyword.toLowerCase())) ? 1.0f : 0.0f;

        // 7. Tag overlap
        String tags = doc.get("tags");
        features[6] = computeTagOverlap(queryTerms, tags);

        // 8. Title length (token count)
        features[7] = (title != null) ? tokenize(title).size() : 0;

        // 9. Content length (log1p of stored snippetSource length as proxy)
        String snippet = doc.get("snippetSource");
        features[8] = (float) Math.log1p(snippet != null ? snippet.length() : 0);

        // 10. Freshness (days since creation)
        String createdAtStr = doc.get("createdAt");
        if (createdAtStr != null) {
            long createdAtMillis = Long.parseLong(createdAtStr);
            long daysSinceCreation = (System.currentTimeMillis() - createdAtMillis) / (1000 * 60 * 60 * 24);
            features[9] = daysSinceCreation;
        }

        // 11. View count (log1p)
        String viewCountStr = doc.get("viewCount");
        features[10] = (float) Math.log1p(viewCountStr != null ? Long.parseLong(viewCountStr) : 0);

        // 12. Like count (log1p) — LongField(Store.YES)로 인덱싱됨 (재색인 후 사용 가능)
        String likeCountStr = doc.get("likeCount");
        features[11] = (float) Math.log1p(likeCountStr != null ? Long.parseLong(likeCountStr) : 0);

        // 13. Category ID (ordinal, 0 = uncategorized)
        String categoryIdStr = doc.get("categoryId");
        features[12] = (categoryIdStr != null) ? Float.parseFloat(categoryIdStr) : 0;

        // 14. Query length (word count)
        features[13] = queryTerms.size();

        return features;
    }

    /**
     * 배치 피처 추출 — Top-N 문서에 대해 한 번에 피처를 추출한다.
     * 학습 데이터 CSV 생성용.
     */
    public List<float[]> extractBatch(IndexSearcher searcher, ScoreDoc[] scoreDocs,
                                      String keyword) throws IOException {
        List<float[]> result = new ArrayList<>(scoreDocs.length);
        for (ScoreDoc sd : scoreDocs) {
            result.add(extractFeatures(searcher, sd.doc, keyword));
        }
        return result;
    }

    /**
     * BM25 score를 특정 필드에 대해 계산한다.
     * Weight.scorer()로 추출 — explain()보다 대량 처리에 효율적.
     *
     * <p>Weight 캐싱: 동일 (field, keyword) 조합에 대해 Weight를 1회만 생성하고
     * 200문서에 재사용한다. Weight 생성(IDF 계산 포함)이 비용이 높으므로,
     * 매 문서마다 생성하면 200 x 3필드 = 600회 → 캐싱 시 3회로 감소.
     */
    private final Map<String, Weight> weightCache = new ConcurrentHashMap<>();

    Weight getOrCreateWeight(IndexSearcher searcher, String field, String keyword) throws IOException {
        String cacheKey = field + ":" + keyword;
        Weight cached = weightCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            var boosts = Map.of(field, 1.0f);
            var parser = new MultiFieldQueryParser(new String[]{field}, analyzer, boosts);
            Query query = parser.parse(MultiFieldQueryParser.escape(keyword));
            Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE, 1.0f);
            weightCache.put(cacheKey, weight);
            return weight;
        } catch (ParseException e) {
            return null;
        }
    }

    /** 배치 시작 전 Weight 캐시를 초기화한다. */
    public void clearWeightCache() {
        weightCache.clear();
    }

    private float computeBM25(IndexSearcher searcher, int docId,
                              String field, String keyword) throws IOException {
        Weight weight = getOrCreateWeight(searcher, field, keyword);
        if (weight == null) return 0.0f;

        for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
            int localDoc = docId - ctx.docBase;
            if (localDoc >= 0 && localDoc < ctx.reader().maxDoc()) {
                Scorer scorer = weight.scorer(ctx);
                if (scorer != null && scorer.iterator().advance(localDoc) == localDoc) {
                    return scorer.score();
                }
                return 0.0f;
            }
        }
        return 0.0f;
    }

    /**
     * query term 중 텍스트에 등장하는 비율을 계산한다.
     */
    private float computeTermCoverage(List<String> queryTerms, String text) {
        if (queryTerms.isEmpty() || text == null || text.isBlank()) return 0.0f;

        List<String> textTerms = tokenize(text);
        Set<String> textTermSet = new HashSet<>(textTerms);

        long matched = queryTerms.stream().filter(textTermSet::contains).count();
        return (float) matched / queryTerms.size();
    }

    /**
     * query term과 태그의 중복 수를 계산한다.
     */
    private float computeTagOverlap(List<String> queryTerms, String tags) {
        if (tags == null || tags.isBlank()) return 0.0f;

        Set<String> tagSet = new HashSet<>(Arrays.asList(tags.toLowerCase().split("\\s+")));
        return (float) queryTerms.stream().filter(tagSet::contains).count();
    }

    /**
     * Nori 형태소 분석기로 텍스트를 토큰화한다.
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("_", new StringReader(text))) {
            CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                tokens.add(termAttr.toString());
            }
            ts.end();
        } catch (IOException e) {
            // tokenization 실패 시 빈 목록
        }
        return tokens;
    }
}
