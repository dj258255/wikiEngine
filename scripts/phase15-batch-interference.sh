#!/bin/bash
# Phase 15-0 Step 3 보충: 배치 빌드 중 워크로드 간섭 측정
# 사용법:
#   터미널 A: bash phase15-batch-interference.sh monitor
#   터미널 B: buildPrefixTopK() 트리거 (매시간 자동 또는 수동)
#   터미널 C: bash phase15-batch-interference.sh benchmark

REDIS_PASSWORD="OF8A4z0bhIEQdyOlLoLAuiLwG3N7eO38czbhRIUR"
REDIS_CLI="docker exec wiki-redis-prod redis-cli -a $REDIS_PASSWORD --no-auth-warning"
OUTPUT_DIR="/tmp/phase15-before"
mkdir -p "$OUTPUT_DIR"

case "$1" in
    monitor)
        echo "=== 레이턴시 모니터링 시작 (Ctrl+C로 중단) ==="
        echo "배치 빌드 시점에 스파이크를 관찰하세요"
        echo "---"
        $REDIS_CLI --latency-history -i 1 2>&1 | tee "$OUTPUT_DIR/latency-history.txt"
        ;;

    benchmark)
        echo "=== 배치 중 redis-benchmark 실행 ==="
        echo "이 명령은 buildPrefixTopK()가 실행 중일 때 돌려야 합니다"
        echo "---"
        echo "배치 중 GET:"
        docker exec wiki-redis-prod redis-benchmark -a "$REDIS_PASSWORD" --no-auth-warning \
            -t GET -n 100000 -c 50 -q 2>&1 | tee "$OUTPUT_DIR/benchmark-during-batch.txt"
        echo ""
        echo "배치 중 SET:"
        docker exec wiki-redis-prod redis-benchmark -a "$REDIS_PASSWORD" --no-auth-warning \
            -t SET -n 100000 -c 50 -q 2>&1 | tee -a "$OUTPUT_DIR/benchmark-during-batch.txt"
        ;;

    compare)
        echo "=== Before/During 비교 ==="
        echo ""
        echo "--- Baseline (배치 없을 때) ---"
        cat "$OUTPUT_DIR/benchmark-baseline.txt" 2>/dev/null || echo "  (아직 측정 안 됨 — phase15-before-measurement.sh 먼저 실행)"
        echo ""
        echo "--- During Batch (배치 중) ---"
        cat "$OUTPUT_DIR/benchmark-during-batch.txt" 2>/dev/null || echo "  (아직 측정 안 됨)"
        ;;

    *)
        echo "사용법:"
        echo "  bash $0 monitor    — 레이턴시 실시간 모니터링"
        echo "  bash $0 benchmark  — 배치 중 벤치마크 실행"
        echo "  bash $0 compare    — baseline vs 배치 중 비교"
        ;;
esac
