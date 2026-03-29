package com.wiki.engine.post.internal.lucene;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LTR 학습 데이터 생성 서비스 — Gemini LLM-as-a-Judge.
 *
 * <p>현업 근거: SIGIR 2023 (Thomas et al.) — LLM의 relevance 판정이
 * crowdsource annotator와 Cohen's Kappa 0.6~0.7로 동등 이상.
 * OpenSource Connections: editorial judgment 100~200 query × 20~50 docs로
 * BM25 대비 NDCG +10~15% 개선 가능.
 *
 * <p>방식: Pointwise + Chain-of-Thought + 4-point scale (0~3).
 * - 0: Irrelevant — 문서가 쿼리와 무관
 * - 1: Marginally relevant — 주제 언급만
 * - 2: Relevant — 부분적 답변
 * - 3: Highly relevant — 직접적이고 완전한 답변
 */
@Slf4j
@Service
public class LTRDataGenerationService {

    private final LuceneSearchService luceneSearchService;
    private final LTRFeatureExtractor featureExtractor;
    private final ChatClient chatClient;

    private static final String JUDGE_PROMPT = """
            You are a search relevance expert. Given a search query and a document,
            rate how relevant the document is to the query.

            Scale:
            - 0 (Irrelevant): Document has no relation to the query
            - 1 (Marginally Relevant): Document mentions the topic but doesn't answer the query
            - 2 (Relevant): Document partially answers or is closely related to the query
            - 3 (Highly Relevant): Document directly and comprehensively answers the query

            Think step by step:
            1. What is the user's intent behind this query?
            2. Does the document address this intent?
            3. How completely does it address the intent?

            Query: %s
            Document Title: %s
            Document Content (first 300 chars): %s

            Respond with ONLY a JSON object: {"reasoning": "brief explanation", "score": N}
            """;

    // 진행 상태 추적
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private volatile boolean running = false;
    private final List<LTRTrainingRow> trainingData = Collections.synchronizedList(new ArrayList<>());

    public LTRDataGenerationService(LuceneSearchService luceneSearchService,
                                     LTRFeatureExtractor featureExtractor,
                                     ChatClient.Builder chatClientBuilder) {
        this.luceneSearchService = luceneSearchService;
        this.featureExtractor = featureExtractor;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 주어진 쿼리 목록에 대해 LTR 학습 데이터를 생성한다.
     * 각 쿼리마다 BM25 Top-N 문서를 추출하고, Gemini로 relevance를 판정한다.
     * Rate limit 대응으로 각 호출 사이에 delay를 둔다.
     *
     * @param queries     검색어 목록
     * @param topN        쿼리당 추출할 문서 수
     * @param delayMillis 호출 간 대기 시간 (ms, rate limit 대응)
     */
    public void generateAsync(List<String> queries, int topN, long delayMillis) {
        if (running) {
            log.warn("LTR 데이터 생성이 이미 진행 중입니다");
            return;
        }

        running = true;
        processedCount.set(0);
        totalCount.set(queries.size() * topN);
        trainingData.clear();

        Thread.startVirtualThread(() -> {
            try {
                for (int qIdx = 0; qIdx < queries.size(); qIdx++) {
                    String query = queries.get(qIdx);
                    log.info("LTR 데이터 생성 진행: query {}/{} — '{}'",
                            qIdx + 1, queries.size(), query);

                    try {
                        var docs = luceneSearchService.extractLTRFeatures(
                                query, topN, featureExtractor);

                        for (var doc : docs) {
                            int relevance = judgeRelevance(query, doc.title(), doc.snippet());
                            trainingData.add(new LTRTrainingRow(
                                    qIdx, query, doc.postId(), doc.title(),
                                    relevance, doc.features()));
                            processedCount.incrementAndGet();

                            if (delayMillis > 0) {
                                Thread.sleep(delayMillis);
                            }
                        }
                    } catch (Exception e) {
                        log.error("LTR 데이터 생성 실패: query='{}', error={}", query, e.getMessage());
                    }
                }
                log.info("LTR 데이터 생성 완료: {} rows", trainingData.size());
            } finally {
                running = false;
            }
        });
    }

    /**
     * Gemini로 (query, document) 쌍의 relevance를 판정한다.
     * 4-point scale (0~3). JSON 응답에서 score만 추출.
     */
    private int judgeRelevance(String query, String title, String snippet) {
        try {
            String prompt = String.format(JUDGE_PROMPT, query, title,
                    snippet != null ? snippet : "");

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseScore(response);
        } catch (Exception e) {
            log.warn("Gemini 판정 실패: query='{}', title='{}', error={}",
                    query, title, e.getMessage());
            return -1; // 판정 실패
        }
    }

    /**
     * Gemini 응답에서 score를 추출한다.
     * {"reasoning": "...", "score": 2} → 2
     */
    private int parseScore(String response) {
        if (response == null) return -1;

        // JSON에서 score 추출
        var scoreMatch = java.util.regex.Pattern.compile("\"score\"\\s*:\\s*(\\d)")
                .matcher(response);
        if (scoreMatch.find()) {
            int score = Integer.parseInt(scoreMatch.group(1));
            return Math.min(Math.max(score, 0), 3); // 0~3 범위 제한
        }

        // fallback: 숫자만 추출
        var numMatch = java.util.regex.Pattern.compile("\\b([0-3])\\b")
                .matcher(response);
        if (numMatch.find()) {
            return Integer.parseInt(numMatch.group(1));
        }

        return -1;
    }

    /**
     * 현재 진행 상태를 반환한다.
     */
    public Map<String, Object> getStatus() {
        return Map.of(
                "running", running,
                "processed", processedCount.get(),
                "total", totalCount.get(),
                "dataSize", trainingData.size()
        );
    }

    /**
     * 생성된 학습 데이터를 CSV 형식으로 반환한다.
     * XGBoost LambdaMART 학습용 포맷.
     */
    public String exportCSV() {
        var sb = new StringBuilder();

        // 헤더: qid, relevance, feature1, ..., feature14, postId, query, title
        sb.append("qid,relevance");
        for (String name : LTRFeatureExtractor.FEATURE_NAMES) {
            sb.append(",").append(name);
        }
        sb.append(",postId,query,title\n");

        for (var row : trainingData) {
            if (row.relevance < 0) continue; // 판정 실패 제외
            sb.append(row.queryIndex).append(",").append(row.relevance);
            for (float f : row.features) {
                sb.append(",").append(f);
            }
            sb.append(",").append(row.postId);
            sb.append(",\"").append(row.query.replace("\"", "\"\"")).append("\"");
            sb.append(",\"").append(row.title.replace("\"", "\"\"")).append("\"");
            sb.append("\n");
        }
        return sb.toString();
    }

    public List<LTRTrainingRow> getTrainingData() {
        return List.copyOf(trainingData);
    }

    public record LTRTrainingRow(int queryIndex, String query, long postId,
                                  String title, int relevance, float[] features) {}
}
