package com.wiki.engine.post.internal.lucene;

import lombok.extern.slf4j.Slf4j;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.apache.lucene.search.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;

/**
 * LTR Rescorer — XGBoost4J로 LambdaMART 모델 추론.
 *
 * <p>Two-Phase Ranking:
 * Phase 1: BM25로 Top-N(200) 후보 추출
 * Phase 2: XGBoost 모델로 재랭킹 → Top-K 반환
 *
 * <p>ONNX Runtime 대신 XGBoost4J를 선택한 이유:
 * - onnxmltools가 XGBRanker ONNX 변환 미지원 (onnxmltools Issue #382)
 * - XGBoost4J는 Python save_model() 포맷을 변환 없이 직접 로드
 * - ARM64 Linux (OCI Ampere A1) 네이티브 라이브러리 JAR에 번들 포함
 * - inplace_predict()가 thread-safe — 웹서버 동시 요청 처리에 적합
 *
 * <p>모델 파일이 없으면 BM25 순위를 그대로 사용 (graceful degradation).
 */
@Slf4j
@Component
public class LTRRescorer {

    @Value("${ltr.model-path:classpath:ltr/model.xgb}")
    private Resource modelResource;

    @Value("${ltr.enabled:false}")
    private boolean enabled;

    @Value("${ltr.rescore-window:200}")
    private int rescoreWindow;

    private Booster booster;
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

            booster = XGBoost.loadModel(modelResource.getInputStream());
            modelLoaded = true;
            log.info("LTR XGBoost 모델 로드 완료: {}", modelResource.getFilename());
        } catch (Exception e) {
            log.error("LTR 모델 로드 실패: {} — BM25 순위 유지", e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        // XGBoost4J Booster는 별도 close 불필요 (GC가 네이티브 리소스 해제)
    }

    /**
     * BM25 Top-N 결과를 XGBoost 모델로 재랭킹한다.
     */
    public ScoreDoc[] rescore(IndexSearcher searcher, TopDocs firstPassTopDocs,
                              String keyword, LTRFeatureExtractor featureExtractor,
                              int topK) throws IOException {
        if (!modelLoaded) {
            return Arrays.copyOf(firstPassTopDocs.scoreDocs,
                    Math.min(topK, firstPassTopDocs.scoreDocs.length));
        }

        int docsToRescore = Math.min(rescoreWindow, firstPassTopDocs.scoreDocs.length);
        if (docsToRescore == 0) {
            return new ScoreDoc[0];
        }
        ScoreDoc[] docs = Arrays.copyOf(firstPassTopDocs.scoreDocs, docsToRescore);

        // 피처 추출
        float[] flatFeatures = new float[docsToRescore * LTRFeatureExtractor.FEATURE_COUNT];
        for (int i = 0; i < docsToRescore; i++) {
            float[] docFeatures = featureExtractor.extractFeatures(searcher, docs[i].doc, keyword);
            System.arraycopy(docFeatures, 0, flatFeatures,
                    i * LTRFeatureExtractor.FEATURE_COUNT, LTRFeatureExtractor.FEATURE_COUNT);
        }

        // XGBoost4J 추론
        float[] ltrScores = predict(flatFeatures, docsToRescore);

        // 추론 실패(null) 시 BM25 원본 순위 유지
        if (ltrScores == null) {
            log.warn("LTR 추론 실패 — BM25 원본 순위 유지");
            return Arrays.copyOf(firstPassTopDocs.scoreDocs,
                    Math.min(topK, firstPassTopDocs.scoreDocs.length));
        }

        // LTR 점수로 재정렬
        for (int i = 0; i < docsToRescore; i++) {
            docs[i].score = ltrScores[i];
        }
        Arrays.sort(docs, (a, b) -> Float.compare(b.score, a.score));

        return Arrays.copyOf(docs, Math.min(topK, docs.length));
    }

    /**
     * XGBoost4J inplace_predict로 배치 추론한다.
     *
     * <p>DMatrix + predict()는 Booster 내부 상태를 변경하여 thread-safe가 아니다.
     * inplace_predict()는 DMatrix 생성 없이 flat float[]로 직접 추론하며,
     * 공유 상태를 변경하지 않아 thread-safe — 웹서버 동시 요청 처리에 적합.
     */
    private float[] predict(float[] flatFeatures, int numRows) {
        try {
            float[][] predictions = booster.inplace_predict(
                    flatFeatures, numRows, LTRFeatureExtractor.FEATURE_COUNT, Float.NaN);
            // predictions[i][0] = i번째 문서의 LTR 점수
            float[] scores = new float[numRows];
            for (int i = 0; i < numRows; i++) {
                scores[i] = predictions[i][0];
            }
            return scores;
        } catch (XGBoostError e) {
            log.error("XGBoost 추론 실패: {}", e.getMessage());
            return null;
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
