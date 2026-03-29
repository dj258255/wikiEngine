"""
LTR(Learning to Rank) 학습 스크립트 — XGBoost LambdaMART + ONNX 변환.

사용법:
    pip install xgboost pandas scikit-learn onnxmltools skl2onnx onnxruntime
    python train_ltr.py ltr_training_data.csv

출력:
    - model.onnx: Java ONNX Runtime에서 로드할 모델 파일
    - model.xgb: XGBoost 네이티브 모델 (백업)
    - evaluation_report.txt: NDCG@10 Before/After 비교
    - feature_importance.csv: 피처 중요도

현업 근거:
    - Booking.com KDD 2019: "GBDT models are hard to beat for tabular features"
    - 소규모 데이터 과적합 방지: max_depth=3, n_estimators=100, subsample=0.8
"""

import sys
import os
import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.model_selection import GroupKFold

# ONNX 변환용 (없으면 건너뜀)
try:
    import onnxmltools
    from onnxmltools.convert import convert_xgboost
    from onnxconverter_common import FloatTensorType
    HAS_ONNX = True
except ImportError:
    HAS_ONNX = False
    print("WARNING: onnxmltools 미설치. ONNX 변환 건너뜁니다.")
    print("         pip install onnxmltools skl2onnx onnxruntime")


FEATURE_NAMES = [
    "bm25Title", "bm25Content", "bm25Snippet",
    "queryTermCoverageTitle", "queryTermCoverageContent",
    "exactTitleMatch", "tagOverlap",
    "titleLength", "contentLength", "freshnessDays",
    "viewCount", "likeCount", "categoryId", "queryLength"
]


def load_data(csv_path):
    """CSV에서 학습 데이터를 로드한다."""
    df = pd.read_csv(csv_path)
    print(f"데이터 로드: {len(df)} rows, {df['qid'].nunique()} queries")
    print(f"Relevance 분포:\n{df['relevance'].value_counts().sort_index()}")
    return df


def compute_ndcg(y_true, y_pred, groups, k=10):
    """그룹별 NDCG@K를 계산한다."""
    ndcgs = []
    idx = 0
    for g in groups:
        group_true = y_true[idx:idx+g]
        group_pred = y_pred[idx:idx+g]

        # 예측 점수로 정렬
        sorted_indices = np.argsort(-group_pred)
        sorted_true = group_true[sorted_indices][:k]

        # DCG
        dcg = np.sum((2**sorted_true - 1) / np.log2(np.arange(len(sorted_true)) + 2))

        # IDCG
        ideal_sorted = np.sort(group_true)[::-1][:k]
        idcg = np.sum((2**ideal_sorted - 1) / np.log2(np.arange(len(ideal_sorted)) + 2))

        if idcg > 0:
            ndcgs.append(dcg / idcg)
        idx += g

    return np.mean(ndcgs) if ndcgs else 0.0


def train_model(df):
    """XGBoost LambdaMART 모델을 학습한다."""
    X = df[FEATURE_NAMES].values
    y = df["relevance"].values
    qids = df["qid"].values

    # 그룹 크기 계산 (qid별 문서 수)
    groups = df.groupby("qid").size().values

    # BM25 baseline NDCG (bm25Title 점수 기준 순위)
    bm25_scores = X[:, 0]  # bm25Title
    baseline_ndcg = compute_ndcg(y.astype(float), bm25_scores, groups, k=10)
    print(f"\nBaseline NDCG@10 (BM25 title score): {baseline_ndcg:.4f}")

    # XGBoost LambdaMART
    # 과적합 방지: 소규모 데이터이므로 강한 정규화
    params = {
        "objective": "rank:ndcg",
        "eval_metric": "ndcg@10",
        "learning_rate": 0.05,
        "max_depth": 3,
        "n_estimators": 100,
        "subsample": 0.8,
        "colsample_bytree": 0.8,
        "min_child_weight": 5,
        "reg_alpha": 0.1,
        "reg_lambda": 1.0,
        "random_state": 42,
    }

    model = xgb.XGBRanker(**params)
    model.fit(X, y, qid=qids, verbose=True)

    # LTR NDCG
    ltr_scores = model.predict(X)
    ltr_ndcg = compute_ndcg(y.astype(float), ltr_scores, groups, k=10)
    print(f"LTR NDCG@10 (XGBoost LambdaMART): {ltr_ndcg:.4f}")
    print(f"개선폭: +{(ltr_ndcg - baseline_ndcg) * 100:.1f}%p")

    # Cross-validation (GroupKFold — 같은 qid가 train/test에 분리)
    gkf = GroupKFold(n_splits=min(5, df["qid"].nunique()))
    cv_ndcgs = []
    for fold, (train_idx, test_idx) in enumerate(gkf.split(X, y, qids)):
        cv_model = xgb.XGBRanker(**params)
        cv_model.fit(X[train_idx], y[train_idx], qid=qids[train_idx], verbose=False)
        cv_scores = cv_model.predict(X[test_idx])
        test_groups = df.iloc[test_idx].groupby("qid").size().values
        cv_ndcg = compute_ndcg(y[test_idx].astype(float), cv_scores, test_groups, k=10)
        cv_ndcgs.append(cv_ndcg)
        print(f"  Fold {fold+1}: NDCG@10 = {cv_ndcg:.4f}")

    print(f"Cross-validation 평균 NDCG@10: {np.mean(cv_ndcgs):.4f} (±{np.std(cv_ndcgs):.4f})")

    # Feature importance
    importance = model.get_booster().get_score(importance_type="gain")
    imp_df = pd.DataFrame([
        {"feature": f"f{i}", "name": FEATURE_NAMES[i],
         "importance": importance.get(f"f{i}", 0)}
        for i in range(len(FEATURE_NAMES))
    ]).sort_values("importance", ascending=False)
    print(f"\nFeature Importance (gain):")
    for _, row in imp_df.iterrows():
        print(f"  {row['name']:30s} {row['importance']:.1f}")

    return model, baseline_ndcg, ltr_ndcg, np.mean(cv_ndcgs), imp_df


def save_model(model, imp_df, baseline_ndcg, ltr_ndcg, cv_ndcg, output_dir):
    """모델과 평가 결과를 저장한다."""
    os.makedirs(output_dir, exist_ok=True)

    # XGBoost 네이티브 모델
    xgb_path = os.path.join(output_dir, "model.xgb")
    model.save_model(xgb_path)
    print(f"\nXGBoost 모델 저장: {xgb_path} ({os.path.getsize(xgb_path) / 1024:.1f} KB)")

    # ONNX 변환
    if HAS_ONNX:
        onnx_path = os.path.join(output_dir, "model.onnx")
        initial_type = [("features", FloatTensorType([None, len(FEATURE_NAMES)]))]
        onnx_model = convert_xgboost(model, initial_types=initial_type)
        onnxmltools.utils.save_model(onnx_model, onnx_path)
        print(f"ONNX 모델 저장: {onnx_path} ({os.path.getsize(onnx_path) / 1024:.1f} KB)")

    # Feature importance CSV
    imp_path = os.path.join(output_dir, "feature_importance.csv")
    imp_df.to_csv(imp_path, index=False)

    # 평가 리포트
    report_path = os.path.join(output_dir, "evaluation_report.txt")
    with open(report_path, "w") as f:
        f.write("LTR Evaluation Report\n")
        f.write("=" * 50 + "\n\n")
        f.write(f"Baseline NDCG@10 (BM25): {baseline_ndcg:.4f}\n")
        f.write(f"LTR NDCG@10 (LambdaMART): {ltr_ndcg:.4f}\n")
        f.write(f"개선폭: +{(ltr_ndcg - baseline_ndcg) * 100:.1f}%p\n")
        f.write(f"Cross-validation NDCG@10: {cv_ndcg:.4f}\n\n")
        f.write("Feature Importance:\n")
        for _, row in imp_df.iterrows():
            f.write(f"  {row['name']:30s} {row['importance']:.1f}\n")
    print(f"평가 리포트: {report_path}")


def main():
    if len(sys.argv) < 2:
        print("Usage: python train_ltr.py <ltr_training_data.csv> [output_dir]")
        sys.exit(1)

    csv_path = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else "ltr_model"

    df = load_data(csv_path)
    model, baseline_ndcg, ltr_ndcg, cv_ndcg, imp_df = train_model(df)
    save_model(model, imp_df, baseline_ndcg, ltr_ndcg, cv_ndcg, output_dir)

    print(f"\n완료! 모델 파일: {output_dir}/model.onnx")
    print("다음 단계: model.onnx를 Spring Boot resources에 복사 후 LTRRescorer 적용")


if __name__ == "__main__":
    main()
