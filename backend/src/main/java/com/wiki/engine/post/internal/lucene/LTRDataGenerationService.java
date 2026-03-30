package com.wiki.engine.post.internal.lucene;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
 *
 * <p>현업 대비 설계 판단:
 * - LinkedIn SAGE는 Frontier LLM → 8B Student → Ultra-compact 3단계 distillation을 사용하지만,
 *   본 프로젝트는 학습 데이터 생성 목적이므로 LLM 직접 판정 + 3회 평균으로 충분.
 * - Walmart은 RAG + few-shot으로 단일 호출하지만, 우리는 쿼리당 20건 소규모이므로
 *   3회 호출 평균 반올림이 비용 대비 분산 감소에 최적 (TREC LLMJudge RMIT-IR 팀 방식).
 * - 현업은 Kafka 큐 기반 비동기 파이프라인 (Airbnb: Kafka → Spark → Airflow)이지만,
 *   LTR 데이터 생성은 1회성 배치 작업이고 병목이 Gemini rate limit(15 RPM)이므로
 *   consumer를 늘려도 처리량이 증가하지 않음. CSV append + resume로 crash-safe 내구성 확보.
 *   Kafka는 Phase 19 Part 3-5 클릭 로그 기반 재학습(실시간 이벤트 수집)에서 활용 예정.
 *
 * <p>Rate limit 대응 (Gemini 무료 티어 15 RPM):
 * - 라운드 간 5초 딜레이 (분당 12 요청 → 15 RPM 이내)
 * - 실패 시 지수 백오프 재시도 (10초 → 20초, 최대 2회)
 * - 디스크 저장: 판정 즉시 CSV append → 서버 재시작 시 데이터 유실 방지
 * - Resume: 이전에 완료된 (qid, postId) 쌍은 건너뜀
 */
@Slf4j
@Service
public class LTRDataGenerationService {

    private final LuceneSearchService luceneSearchService;
    private final LTRFeatureExtractor featureExtractor;
    private final ChatClient chatClient;

    @Value("${ltr.data-path:ltr_training_data.csv}")
    private String dataPath;

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

    private static final int JUDGE_ROUNDS = 3;
    private static final long ROUND_DELAY_MS = 5_000;       // 15 RPM 대응: 분당 12 요청
    private static final int MAX_RETRIES = 2;                // 라운드당 최대 2회 재시도
    private static final long RETRY_BASE_DELAY_MS = 10_000;  // 재시도 백오프: 10초 → 20초

    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private volatile boolean running = false;

    public LTRDataGenerationService(LuceneSearchService luceneSearchService,
                                     LTRFeatureExtractor featureExtractor,
                                     ChatClient.Builder chatClientBuilder) {
        this.luceneSearchService = luceneSearchService;
        this.featureExtractor = featureExtractor;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * LTR 학습 데이터를 생성한다.
     * 각 행은 판정 즉시 CSV 파일에 flush. 이전 실행 결과가 있으면 resume.
     */
    public void generateAsync(List<String> queries, int topN, long delayMillis) {
        if (running) {
            log.warn("LTR 데이터 생성이 이미 진행 중입니다");
            return;
        }

        running = true;
        processedCount.set(0);
        totalCount.set(queries.size() * topN);
        successCount.set(0);
        failCount.set(0);

        Thread.startVirtualThread(() -> {
            Path csvPath = Path.of(dataPath);

            // Resume: 이전에 완료된 쌍 로드
            Set<String> completed = loadCompletedKeys(csvPath);
            if (!completed.isEmpty()) {
                log.info("이전 실행 데이터 {} 건 발견 — resume 모드", completed.size());
            }

            try (var writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(csvPath.toFile(), !completed.isEmpty()),  // append if resuming
                    StandardCharsets.UTF_8))) {

                // 새 파일이면 헤더 작성
                if (completed.isEmpty()) {
                    writer.write(csvHeader());
                    writer.newLine();
                    writer.flush();
                }

                for (int qIdx = 0; qIdx < queries.size(); qIdx++) {
                    String query = queries.get(qIdx);
                    log.info("LTR 진행: query {}/{} — '{}' (성공: {}, 실패: {}, skip: {})",
                            qIdx + 1, queries.size(), query,
                            successCount.get(), failCount.get(), completed.size());

                    try {
                        var docs = luceneSearchService.extractLTRFeatures(
                                query, topN, featureExtractor);

                        for (var doc : docs) {
                            processedCount.incrementAndGet();

                            // Resume: 이미 완료된 쌍 건너뛰기
                            String key = qIdx + ":" + doc.postId();
                            if (completed.contains(key)) {
                                continue;
                            }

                            int relevance = judgeRelevance(query, doc.title(), doc.snippet());

                            if (relevance >= 0) {
                                successCount.incrementAndGet();
                                writer.write(csvRow(qIdx, relevance, doc, query));
                                writer.newLine();
                                writer.flush();
                            } else {
                                failCount.incrementAndGet();
                            }

                            if (delayMillis > 0) {
                                sleep(delayMillis);
                            }
                        }
                    } catch (Exception e) {
                        log.error("LTR 실패: query='{}', error={}", query, e.getMessage());
                    }
                }
                log.info("LTR 데이터 생성 완료 (성공: {}, 실패: {})", successCount.get(), failCount.get());
            } catch (IOException e) {
                log.error("CSV 파일 오류: {}", e.getMessage());
            } finally {
                running = false;
            }
        });
    }

    /**
     * Gemini로 (query, document) 쌍의 relevance를 판정한다.
     * 3회 호출 → 평균 → 반올림 (non-deterministic 대응).
     *
     * <p>현업 근거: TREC LLMJudge 참가팀(RMIT-IR) — 3회 생성 후 평균 반올림.
     * Graded relevance(0~3)에서는 Majority Voting보다 Averaging이 분산을 줄여 적합.
     * temperature=0이어도 GPU batch 구성/부동소수점 비결합성으로 완전 deterministic 아님.
     * 출처: arxiv 2404.18796 (PoLL), arxiv 2412.12509 (Trust LLM Judgments)
     *
     * <p>실패 시 지수 백오프 재시도 (Google Vertex AI 공식 권장 패턴).
     * 라운드당 최대 2회 재시도, 10초 → 20초 대기.
     */
    private int judgeRelevance(String query, String title, String snippet) {
        String prompt = String.format(JUDGE_PROMPT, query, title,
                snippet != null ? snippet : "");

        List<Integer> scores = new ArrayList<>();
        for (int round = 0; round < JUDGE_ROUNDS; round++) {
            int score = callWithRetry(prompt, query, title, round);
            if (score >= 0) {
                scores.add(score);
            }
            if (round < JUDGE_ROUNDS - 1) {
                sleep(ROUND_DELAY_MS);
            }
        }

        if (scores.isEmpty()) return -1;
        double avg = scores.stream().mapToInt(Integer::intValue).average().orElse(-1);
        return (int) Math.round(avg);
    }

    /**
     * Gemini API 호출 + 지수 백오프 재시도 (10초 → 20초).
     */
    private int callWithRetry(String prompt, String query, String title, int round) {
        for (int retry = 0; retry <= MAX_RETRIES; retry++) {
            try {
                if (retry > 0) {
                    long backoff = RETRY_BASE_DELAY_MS * (1L << (retry - 1));
                    log.debug("재시도 대기 {}ms (round {}, retry {})", backoff, round + 1, retry);
                    sleep(backoff);
                }
                String response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                return parseScore(response);
            } catch (Exception e) {
                if (retry == MAX_RETRIES) {
                    log.warn("Gemini 판정 실패 (round {}, {}회 시도): query='{}', title='{}', error={}",
                            round + 1, retry + 1, query, truncate(title, 30), e.getMessage());
                }
            }
        }
        return -1;
    }

    private int parseScore(String response) {
        if (response == null) return -1;
        var scoreMatch = java.util.regex.Pattern.compile("\"score\"\\s*:\\s*(\\d)")
                .matcher(response);
        if (scoreMatch.find()) {
            int score = Integer.parseInt(scoreMatch.group(1));
            return Math.min(Math.max(score, 0), 3);
        }
        var numMatch = java.util.regex.Pattern.compile("\\b([0-3])\\b")
                .matcher(response);
        if (numMatch.find()) {
            return Integer.parseInt(numMatch.group(1));
        }
        return -1;
    }

    // ── Resume 지원: 이전에 완료된 (qid:postId) 쌍 로드 ──

    private Set<String> loadCompletedKeys(Path csvPath) {
        Set<String> keys = new HashSet<>();
        if (!Files.exists(csvPath)) return keys;
        try (var reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            reader.readLine(); // 헤더 스킵
            String line;
            while ((line = reader.readLine()) != null) {
                // CSV: qid,relevance,...,postId,query,title
                String[] parts = line.split(",", 17); // 16 columns + title
                if (parts.length >= 16) {
                    String qid = parts[0];
                    String postId = parts[15]; // postId는 16번째 컬럼
                    keys.add(qid + ":" + postId);
                }
            }
        } catch (IOException e) {
            log.warn("이전 CSV 읽기 실패 (새로 생성): {}", e.getMessage());
        }
        return keys;
    }

    // ── CSV 포맷 ──

    private String csvHeader() {
        var sb = new StringBuilder("qid,relevance");
        for (String name : LTRFeatureExtractor.FEATURE_NAMES) {
            sb.append(",").append(name);
        }
        sb.append(",postId,query,title");
        return sb.toString();
    }

    private String csvRow(int qIdx, int relevance, LuceneSearchService.LTRDocFeatures doc, String query) {
        var sb = new StringBuilder();
        sb.append(qIdx).append(",").append(relevance);
        for (float f : doc.features()) {
            sb.append(",").append(f);
        }
        sb.append(",").append(doc.postId());
        sb.append(",\"").append(query.replace("\"", "\"\"")).append("\"");
        sb.append(",\"").append(doc.title().replace("\"", "\"\"")).append("\"");
        return sb.toString();
    }

    // ── Status / Export ──

    public Map<String, Object> getStatus() {
        var status = new LinkedHashMap<String, Object>();
        status.put("running", running);
        status.put("processed", processedCount.get());
        status.put("total", totalCount.get());
        status.put("success", successCount.get());
        status.put("fail", failCount.get());
        status.put("csvPath", dataPath);
        return status;
    }

    public String exportCSV() {
        Path csvPath = Path.of(dataPath);
        if (Files.exists(csvPath)) {
            try {
                return Files.readString(csvPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("CSV 읽기 실패: {}", e.getMessage());
            }
        }
        return csvHeader() + "\n";
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
