package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.function.FunctionScoreQuery;
import org.apache.lucene.search.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 키워드 검색.
     * title과 content 필드를 동시에 검색하며, title에 더 높은 가중치를 부여한다.
     *
     * @return DB에서 조회한 Post 엔티티 Page (Lucene은 ID만 반환, 상세는 DB 조회)
     */
    public Page<Post> search(String keyword, Pageable pageable) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query = buildQuery(keyword);
            int offset = (int) pageable.getOffset();
            int limit = pageable.getPageSize();

            // 전체 매칭 수 파악을 위해 offset + limit 만큼 검색
            TopDocs topDocs = searcher.search(query, offset + limit);
            long totalHits = topDocs.totalHits.value();

            // offset 이후의 결과에서 ID 추출
            StoredFields storedFields = searcher.storedFields();
            List<Long> postIds = new ArrayList<>();
            for (int i = offset; i < Math.min(topDocs.scoreDocs.length, offset + limit); i++) {
                Document doc = storedFields.document(topDocs.scoreDocs[i].doc);
                postIds.add(Long.parseLong(doc.get("id")));
            }

            if (postIds.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, totalHits);
            }

            // Lucene 결과 순서를 유지하며 DB에서 엔티티 조회
            List<Post> posts = postRepository.findAllById(postIds);
            posts.sort((a, b) -> postIds.indexOf(a.getId()) - postIds.indexOf(b.getId()));

            return new PageImpl<>(posts, pageable, totalHits);

        } catch (ParseException e) {
            log.warn("검색어 파싱 실패: keyword={}, error={}", keyword, e.getMessage());
            return new PageImpl<>(List.of(), pageable, 0);
        } finally {
            searcherManager.release(searcher);
        }
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
     * BM25 텍스트 관련성 + 인기도(viewCount, likeCount) + 최신성(recency decay)을 결합한 쿼리.
     *
     * final_score = BM25(title^3, content^1)          // MUST: 텍스트 관련성
     *             + satu(viewCount, w=3.0, pivot=1000) // SHOULD: 조회수 부스트
     *             + satu(likeCount, w=2.0, pivot=100)  // SHOULD: 좋아요 부스트
     *             + recencyBoost(createdAt)             // SHOULD: 최신성 감쇠
     */
    private Query buildQuery(String keyword) throws ParseException {
        // 1. BM25 텍스트 관련성 쿼리
        var boosts = java.util.Map.of("title", 3.0f, "content", 1.0f);
        var parser = new MultiFieldQueryParser(new String[]{"title", "content"}, analyzer, boosts);
        parser.setPhraseSlop(2);
        Query textQuery = parser.parse(escapePreservingPhrases(keyword));

        // 2. 인기도 부스트 (FeatureField saturation — BlockMaxWAND 호환)
        Query viewBoost = FeatureField.newSaturationQuery("features", "viewCount", 3.0f, 1000);
        Query likeBoost = FeatureField.newSaturationQuery("features", "likeCount", 2.0f, 100);

        // 3. 최신성 감쇠 (exponential decay, 반감기 30일)
        Query recencyBoost = buildRecencyBoost(5.0f, 30);

        // 4. MUST(텍스트) + SHOULD(인기도 + 최신성)
        return new BooleanQuery.Builder()
                .add(textQuery, BooleanClause.Occur.MUST)
                .add(viewBoost, BooleanClause.Occur.SHOULD)
                .add(likeBoost, BooleanClause.Occur.SHOULD)
                .add(recencyBoost, BooleanClause.Occur.SHOULD)
                .build();
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
