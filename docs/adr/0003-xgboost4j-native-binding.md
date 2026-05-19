# 0003. XGBoost4J 네이티브 바인딩 (ONNX 우회)

- **상태**: Accepted
- **결정일**: 2026-02-20
- **관련 ADR**: [#0001](./0001-lucene-direct-vs-elasticsearch.md), [#0004](./0004-disjunction-max-query-cjk.md)

## Context

LTR(Learning to Rank) 단계에서 BM25 Top-N (N=200) 후보를 **XGBoost LambdaMART 모델로 re-rank** 한다. 학습은 Python(scikit-learn, xgboost)으로 진행하고, 추론은 Java(Spring Boot) 안에서 수행해야 한다.

요구사항:

- **인-프로세스 추론** — Lucene 결과를 받아 즉시 re-rank, RPC/IPC 없이
- **Thread-safe** — Tomcat 의 다중 요청 처리 스레드에서 동시 호출
- **응답 P95 < 50 ms** — 14 피처 × 200 후보 추론이 검색 응답에 추가됨
- **모델 핫리로드** — 재학습 시 앱 재배포 없이 모델 교체

## Decision Drivers

1. **추론 응답 시간** (최우선 — 검색 핫패스)
2. **Thread safety**
3. **Python ↔ Java 학습/추론 경계 분리**
4. **운영 단순성**

## Considered Options

### Option A: ONNX Runtime (변환 후 onnxruntime-java)

**원래 1순위 후보였다**. 학계·산업계 표준 흐름:

```
Python xgboost → ONNX 변환 → onnxruntime-java 로 추론
```

- 장점:
  - 표준화된 모델 포맷, 여러 프레임워크 호환
  - onnxruntime 이 thread-safe 보장 + 최적화된 그래프 실행
- 단점:
  - **차단점**: XGBoost4J Issue [#382](https://github.com/dmlc/xgboost/issues/382) — XGBoost → ONNX 변환이 `LambdaMART` 의 일부 트리 구조에서 부정확하게 동작
  - `skl2onnx` 와 `onnxmltools` 양쪽 다 LambdaMART 의 group-wise objective 를 완전히 보존하지 않음 (2026 초 기준)
  - 변환 후 NDCG 가 학습 시점 대비 1~2 %p 떨어지는 현상 확인

### Option B: REST API 분리 (Python FastAPI 서비스)

- 장점: 학습/추론 환경 일관성, Python 생태계 그대로 사용
- 단점:
  - 추가 프로세스 → RPC 비용 (검색마다 HTTP 호출 = P95 폭증)
  - 12 GB 머신에 또 다른 프로세스 — [ADR-0001](./0001-lucene-direct-vs-elasticsearch.md) 와 같은 메모리 압박

### Option C: XGBoost4J 네이티브 바인딩 (선택)

```
Python xgboost.save_model("model.xgb")  ← Booster binary format
                  ↓
Java: Booster.loadModel("model.xgb") via XGBoost4J JNI
```

- 장점:
  - **모델 포맷 100% 호환** — Python xgboost 와 동일한 Booster binary 그대로 사용 → NDCG 손실 0
  - **`inplace_predict` 가 thread-safe** — 같은 Booster 인스턴스를 여러 스레드에서 동시 호출 가능 (DMatrix 생성 없이 raw float array 입력)
  - JNI 호출 → in-process 추론
- 단점:
  - JNI 의존성 (`libxgboost4j.so` 네이티브 라이브러리 포함)
  - XGBoost4J 의 maven 좌표가 ML 프로젝트 표준은 아님

## Decision

**Option C — XGBoost4J 네이티브 바인딩** 선택.

핵심 근거는 **Issue #382**: ONNX 변환이 LambdaMART 의 학습 품질을 100% 보존하지 못한다는 점에서 Option A 는 검색 품질 저하를 수반한다. NDCG@10 의 1~2 %p 손실은 200 쌍 학습 데이터로 +4.8 %p 끌어올린 것을 절반 가까이 까먹는 수준 — 받아들일 수 없다.

`inplace_predict` 의 thread-safety 가 확인되면서 (XGBoost4J 1.7+) Java 안에서 별도 락 없이 호출 가능하다는 점이 결정타였다. Booster 한 개를 싱글톤으로 두고 14 피처 float array 만 만들어 넘기면 된다.

```java
// XGBoostRanker.java (개념도)
private volatile Booster booster;  // 핫리로드 대상

public float[] score(float[][] features) {
    // features: [200 documents][14 features]
    return booster.inplace_predict(features);  // thread-safe
}
```

## Consequences

### Positive

- **NDCG 손실 0** — Python 학습 결과를 그대로 추론에 사용
- **Thread-safe 단일 인스턴스** — 락·풀링 없이 멀티 요청 동시 처리
- **모델 핫리로드** — `Booster.loadModel(path)` 호출만으로 무중단 교체
- **추론 비용 낮음** — 200 후보 × 14 피처 추론이 ms 단위

### Negative

- **네이티브 라이브러리 배포 부담** — `libxgboost4j.so` 가 컨테이너 이미지에 포함되어야 함 (`libgomp1` 의존성도 함께 — Dockerfile 에 명시되어 있음)
- **XGBoost4J 버전 의존** — XGBoost 메이저 버전 업데이트 시 검증 필요
- **메모리** — 모델 binary + Booster 인스턴스가 JVM heap 안에 존재 (현재 모델 약 5 MB, 무시 가능)

### Neutral

- 학습은 Python(`backend/scripts/train_ltr.py`), 추론만 Java — 책임 분리 자연스러움

## Validation

- **추론 P95 < 50 ms** — 검색 응답 P95 2.61 s 안에 포함되어 측정. 단독 측정 시 5~15 ms.
- **모델 핫리로드 성공률** — `/api/admin/ltr/reload` 호출 후 로그에 NDCG 회귀 테스트 결과 출력
- **NDCG@10 CV +4.8 %p** — Gemini LLM-as-a-Judge 학습 데이터 200 쌍 기준, 충족 ([README.md](../../README.md))

만약 XGBoost4J 가 deprecate 되거나 ONNX 변환이 LambdaMART 를 정확히 지원하게 되면 (Issue #382 close) 재검토.

## References

- 구현: `backend/src/main/java/com/wiki/engine/post/internal/lucene/XGBoostRanker.java`
- 피처 추출: `FeatureExtractor.java` (14 피처: BM25 3필드 + 태그 중복 + 문서 시그널)
- 학습 스크립트: `backend/scripts/train_ltr.py`
- 학습 데이터: `backend/scripts/ltr_training_data.csv`
- 관련 Issue: [dmlc/xgboost#382 — ONNX export](https://github.com/dmlc/xgboost/issues/382)
- XGBoost4J `inplace_predict` thread-safety: [공식 문서](https://xgboost.readthedocs.io/en/stable/jvm/javadocs/ml/dmlc/xgboost4j/java/Booster.html)
