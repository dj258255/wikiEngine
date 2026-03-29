package com.wiki.engine.post.internal.lucene;

import ai.onnxruntime.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.search.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;

/**
 * LTR Rescorer — ONNX Runtime으로 XGBoost LambdaMART 모델 추론.
 *
 * <p>Two-Phase Ranking 패턴:
 * Phase 1: BM25로 Top-N(200) 후보 추출 (ms 단위)
 * Phase 2: LTR 모델로 재랭킹 → Top-K 반환
 *
 * <p>현업 기준:
 * - Rescore window N=200이 표준 (Elasticsearch 공식 문서 기본값)
 * - ONNX Runtime 추론: ~0.2ms / 200 documents
 * - 총 추가 latency: 피처 추출 2~5ms + 추론 ~0.5ms = 3~6ms
 *
 * <p>모델 파일이 없으면 LTR을 건너뛰고 BM25 순위를 그대로 사용한다 (graceful degradation).
 */
@Slf4j
@Component
public class LTRRescorer {

    @Value("${ltr.model-path:classpath:ltr/model.onnx}")
    private Resource modelResource;

    @Value("${ltr.enabled:false}")
    private boolean enabled;

    @Value("${ltr.rescore-window:200}")
    private int rescoreWindow;

    private OrtEnvironment env;
    private OrtSession session;
    private boolean modelLoaded = false;

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("LTR 비활성화 (ltr.enabled=false)");
            return;
        }

        try {
            if (!modelResource.exists()) {
                log.warn("LTR 모델 파일 없음: {} — BM25 순위 유지", modelResource);
                return;
            }

            env = OrtEnvironment.getEnvironment();
            byte[] modelBytes = modelResource.getInputStream().readAllBytes();
            session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            modelLoaded = true;
            log.info("LTR 모델 로드 완료: {} ({} KB)",
                    modelResource.getFilename(), modelBytes.length / 1024);
        } catch (Exception e) {
            log.error("LTR 모델 로드 실패: {} — BM25 순위 유지", e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * BM25 Top-N 결과를 LTR 모델로 재랭킹한다.
     *
     * @param searcher         IndexSearcher
     * @param firstPassTopDocs BM25 검색 결과
     * @param keyword          검색어
     * @param featureExtractor 피처 추출기
     * @param topK             반환할 상위 결과 수
     * @return 재랭킹된 ScoreDoc 배열 (topK개)
     */
    public ScoreDoc[] rescore(IndexSearcher searcher, TopDocs firstPassTopDocs,
                              String keyword, LTRFeatureExtractor featureExtractor,
                              int topK) throws IOException {
        if (!modelLoaded) {
            // 모델 없으면 원래 순위 유지
            return Arrays.copyOf(firstPassTopDocs.scoreDocs,
                    Math.min(topK, firstPassTopDocs.scoreDocs.length));
        }

        int docsToRescore = Math.min(rescoreWindow, firstPassTopDocs.scoreDocs.length);
        ScoreDoc[] docs = Arrays.copyOf(firstPassTopDocs.scoreDocs, docsToRescore);

        // 피처 추출
        float[][] featureMatrix = new float[docsToRescore][LTRFeatureExtractor.FEATURE_COUNT];
        for (int i = 0; i < docsToRescore; i++) {
            featureMatrix[i] = featureExtractor.extractFeatures(
                    searcher, docs[i].doc, keyword);
        }

        // ONNX Runtime 추론
        float[] ltrScores = predict(featureMatrix);

        // LTR 점수로 재정렬
        for (int i = 0; i < docsToRescore; i++) {
            docs[i].score = ltrScores[i];
        }
        Arrays.sort(docs, (a, b) -> Float.compare(b.score, a.score));

        return Arrays.copyOf(docs, Math.min(topK, docs.length));
    }

    /**
     * ONNX Runtime으로 배치 추론한다.
     */
    private float[] predict(float[][] features) {
        try {
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, features);
            try (OrtSession.Result result = session.run(
                    Map.of(session.getInputNames().iterator().next(), inputTensor))) {
                // XGBoost ONNX는 보통 (N, 1) 또는 (N,) 형태로 출력
                Object output = result.get(0).getValue();
                if (output instanceof float[][] matrix) {
                    float[] scores = new float[matrix.length];
                    for (int i = 0; i < matrix.length; i++) {
                        scores[i] = matrix[i][0];
                    }
                    return scores;
                } else if (output instanceof float[] array) {
                    return array;
                } else {
                    log.warn("예상치 못한 ONNX 출력 타입: {}", output.getClass());
                    return new float[features.length];
                }
            } finally {
                inputTensor.close();
            }
        } catch (OrtException e) {
            log.error("ONNX 추론 실패: {}", e.getMessage());
            return new float[features.length]; // 실패 시 0점 (원래 순위 유지)
        }
    }

    public boolean isModelLoaded() {
        return modelLoaded;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getRescoreWindow() {
        return rescoreWindow;
    }
}
