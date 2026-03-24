package com.wiki.engine.post;

import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.post.dto.PostSummaryResponse;
import com.wiki.engine.post.internal.LuceneIndexService;
import com.wiki.engine.post.internal.LuceneSearchService;
import com.wiki.engine.post.internal.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.wiki.engine.post.internal.LuceneSearchService.EvalDoc;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lucene 관리용 엔드포인트.
 * 배치 인덱싱 트리거 및 Lucene 검색 테스트용.
 */
@Slf4j
@RestController
@RequestMapping(path = "/admin/lucene", version = "1.0")
@RequiredArgsConstructor
public class PostAdminController {

    private final LuceneIndexService luceneIndexService;
    private final LuceneSearchService luceneSearchService;
    private final PostRepository postRepository;
    private final Analyzer analyzer;

    /**
     * 전체 배치 인덱싱 트리거.
     * posts 테이블 전체를 Lucene에 인덱싱한다.
     */
    @PostMapping("/index-all")
    public ResponseEntity<Map<String, String>> indexAll(
            @RequestParam(defaultValue = "0") long startId) throws IOException {
        long start = System.currentTimeMillis();
        luceneIndexService.indexAll(startId);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "elapsed", elapsed + "ms"
        ));
    }

    /**
     * 특정 게시글만 재인덱싱.
     * DB에서 최신 데이터를 읽어 Lucene 인덱스를 갱신한다.
     * viewCount/likeCount 변경 후 전체 재인덱싱 없이 즉시 반영할 때 사용.
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@RequestParam List<Long> ids) throws IOException {
        List<Long> indexed = new ArrayList<>();
        List<Long> notFound = new ArrayList<>();

        for (Long id : ids) {
            Post post = postRepository.findById(id).orElse(null);
            if (post == null) {
                notFound.add(id);
                continue;
            }
            luceneIndexService.indexPost(post);
            indexed.add(id);
        }

        return ResponseEntity.ok(Map.of(
                "indexed", indexed,
                "notFound", notFound
        ));
    }

    /**
     * Lucene 검색 테스트.
     * 기존 FULLTEXT 검색과 별도로 Lucene 검색 결과를 확인한다.
     */
    @GetMapping("/search")
    public Slice<PostSummaryResponse> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) throws IOException {

        return luceneSearchService.search(q, null, pageable).posts().map(PostSummaryResponse::from);
    }

    /**
     * 검색 품질 평가 (P@10, MAP).
     * 15개 테스트 쿼리로 BM25 only vs 전체 랭킹(BM25 + 인기도 + 최신성)을 비교한다.
     *
     * 관련성 판정: 쿼리 키워드의 50% 이상이 제목에 포함되면 relevant로 판정.
     * 위키 덤프 한계로 클릭 로그 대신 제목 매칭 휴리스틱을 사용한다.
     */
    @GetMapping("/evaluate")
    public Map<String, Object> evaluate() throws IOException {
        List<String> testQueries = List.of(
                "삼성전자", "삼성전자 반도체", "인공지능 기술",
                "대한민국 역사", "프로그래밍", "양자역학",
                "축구", "한국전쟁", "서울 지하철",
                "자바 프로그래밍", "기후변화", "반도체 공정",
                "인터넷", "민주주의", "물리학"
        );

        List<Map<String, Object>> queryResults = new ArrayList<>();
        double bm25P10Sum = 0, fullP10Sum = 0;
        double bm25MapSum = 0, fullMapSum = 0;

        for (String query : testQueries) {
            List<EvalDoc> bm25Results = luceneSearchService.searchForEval(query, 10, true);
            List<EvalDoc> fullResults = luceneSearchService.searchForEval(query, 10, false);

            String[] keywords = query.split("\\s+");

            // P@10 계산
            double bm25P10 = computePrecisionAtK(bm25Results, keywords, 10);
            double fullP10 = computePrecisionAtK(fullResults, keywords, 10);

            // MAP 계산
            double bm25Ap = computeAveragePrecision(bm25Results, keywords);
            double fullAp = computeAveragePrecision(fullResults, keywords);

            bm25P10Sum += bm25P10;
            fullP10Sum += fullP10;
            bm25MapSum += bm25Ap;
            fullMapSum += fullAp;

            queryResults.add(Map.of(
                    "query", query,
                    "bm25Only", Map.of(
                            "p10", Math.round(bm25P10 * 1000) / 1000.0,
                            "ap", Math.round(bm25Ap * 1000) / 1000.0,
                            "results", formatResults(bm25Results, keywords)
                    ),
                    "fullRanking", Map.of(
                            "p10", Math.round(fullP10 * 1000) / 1000.0,
                            "ap", Math.round(fullAp * 1000) / 1000.0,
                            "results", formatResults(fullResults, keywords)
                    )
            ));
        }

        int n = testQueries.size();
        Map<String, Object> bm25Summary = Map.of(
                "avgP10", Math.round(bm25P10Sum / n * 1000) / 1000.0,
                "MAP", Math.round(bm25MapSum / n * 1000) / 1000.0
        );
        Map<String, Object> fullSummary = Map.of(
                "avgP10", Math.round(fullP10Sum / n * 1000) / 1000.0,
                "MAP", Math.round(fullMapSum / n * 1000) / 1000.0
        );

        double p10Improvement = (bm25P10Sum > 0)
                ? Math.round((fullP10Sum - bm25P10Sum) / bm25P10Sum * 1000) / 10.0
                : 0;
        double mapImprovement = (bm25MapSum > 0)
                ? Math.round((fullMapSum - bm25MapSum) / bm25MapSum * 1000) / 10.0
                : 0;

        return Map.of(
                "summary", Map.of(
                        "bm25Only", bm25Summary,
                        "fullRanking", fullSummary,
                        "improvement", Map.of(
                                "p10", p10Improvement + "%",
                                "MAP", mapImprovement + "%"
                        ),
                        "testQueryCount", n,
                        "method", "제목-키워드 매칭 휴리스틱 (쿼리 키워드 50%+ 포함 시 relevant)"
                ),
                "queries", queryResults
        );
    }

    /** P@K: 상위 K개 결과 중 관련 문서 비율 */
    private double computePrecisionAtK(List<EvalDoc> results, String[] keywords, int k) {
        long relevant = results.stream()
                .limit(k)
                .filter(doc -> isRelevant(doc.title(), keywords))
                .count();
        return (double) relevant / Math.min(k, Math.max(results.size(), 1));
    }

    /** AP (Average Precision): 관련 문서가 상위에 올수록 높은 점수 */
    private double computeAveragePrecision(List<EvalDoc> results, String[] keywords) {
        int relevantCount = 0;
        double precisionSum = 0;

        for (int i = 0; i < results.size(); i++) {
            if (isRelevant(results.get(i).title(), keywords)) {
                relevantCount++;
                precisionSum += (double) relevantCount / (i + 1);
            }
        }
        return relevantCount > 0 ? precisionSum / relevantCount : 0;
    }

    /** 관련성 판정: 쿼리 키워드의 50% 이상이 제목에 포함되면 relevant */
    private boolean isRelevant(String title, String[] keywords) {
        if (title == null) return false;
        // "틀:", "분류:" 접두사는 위키 메타 페이지이므로 관련성 낮음
        if (title.startsWith("틀:") || title.startsWith("분류:")) return false;

        long matches = Arrays.stream(keywords)
                .filter(kw -> title.contains(kw))
                .count();
        return matches >= Math.ceil(keywords.length / 2.0);
    }

    private List<Map<String, Object>> formatResults(List<EvalDoc> results, String[] keywords) {
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            EvalDoc doc = results.get(i);
            formatted.add(Map.of(
                    "rank", i + 1,
                    "id", doc.id(),
                    "title", doc.title(),
                    "viewCount", doc.viewCount(),
                    "score", Math.round(doc.score() * 100) / 100.0,
                    "relevant", isRelevant(doc.title(), keywords)
            ));
        }
        return formatted;
    }

    /**
     * Nori 형태소 분석 결과 확인.
     * 입력 텍스트를 KoreanAnalyzer가 어떤 토큰으로 분해하는지 반환한다.
     * ngram과의 비교 검증용.
     */
    @GetMapping("/analyze")
    public List<Map<String, String>> analyze(@RequestParam String text) throws IOException {
        List<Map<String, String>> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("content", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            TypeAttribute type = stream.addAttribute(TypeAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(Map.of("token", term.toString(), "type", type.type()));
            }
            stream.end();
        }
        return tokens;
    }
}
