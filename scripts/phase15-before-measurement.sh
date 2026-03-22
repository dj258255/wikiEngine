#!/bin/bash
# Phase 15-0: Before 실측 스크립트
# 서버 1(168.107.53.115)에서 실행
# 사용법: bash phase15-before-measurement.sh

REDIS_PASSWORD="OF8A4z0bhIEQdyOlLoLAuiLwG3N7eO38czbhRIUR"
REDIS_CLI="docker exec wiki-redis-prod redis-cli -a $REDIS_PASSWORD --no-auth-warning"
OUTPUT_DIR="/tmp/phase15-before"
mkdir -p "$OUTPUT_DIR"

echo "============================================"
echo "Phase 15-0: Before 실측"
echo "시간: $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================"

# ──────────────────────────────────────
# Step 1: 현재 Redis 상태
# ──────────────────────────────────────
echo ""
echo "=== Step 1: 현재 Redis 상태 ==="

echo "--- INFO memory ---"
$REDIS_CLI INFO memory | grep -E "used_memory_human|maxmemory_human|mem_fragmentation_ratio|used_memory_peak_human" | tee "$OUTPUT_DIR/info-memory.txt"

echo ""
echo "--- INFO keyspace ---"
$REDIS_CLI INFO keyspace | tee "$OUTPUT_DIR/info-keyspace.txt"

echo ""
echo "--- 총 키 수 ---"
$REDIS_CLI DBSIZE | tee -a "$OUTPUT_DIR/info-keyspace.txt"

echo ""
echo "--- INFO commandstats (상위 10개) ---"
$REDIS_CLI INFO commandstats | sort -t'=' -k2 -rn | head -20 | tee "$OUTPUT_DIR/info-commandstats.txt"

echo ""
echo "--- maxmemory-policy ---"
$REDIS_CLI CONFIG GET maxmemory-policy | tee "$OUTPUT_DIR/maxmemory-policy.txt"

# ──────────────────────────────────────
# Step 2: KEYS 블로킹 실측
# ──────────────────────────────────────
echo ""
echo "=== Step 2: KEYS 블로킹 실측 ==="

echo "--- post:views:* 키 수 ---"
VIEWS_COUNT=$($REDIS_CLI KEYS "post:views:*" 2>/dev/null | wc -l)
echo "post:views:* 키 수: $VIEWS_COUNT" | tee "$OUTPUT_DIR/keys-count.txt"

echo ""
echo "--- 전체 prefix:* 키 수 ---"
PREFIX_COUNT=$($REDIS_CLI KEYS "prefix:*" 2>/dev/null | wc -l)
echo "prefix:* 키 수: $PREFIX_COUNT" | tee -a "$OUTPUT_DIR/keys-count.txt"

echo ""
echo "--- 전체 키 패턴별 분포 ---"
for pattern in "prefix:*" "post:*" "search:*" "blacklist:*" "post:views:*"; do
    count=$($REDIS_CLI KEYS "$pattern" 2>/dev/null | wc -l)
    echo "$pattern: $count" | tee -a "$OUTPUT_DIR/keys-count.txt"
done

echo ""
echo "--- KEYS post:views:* 소요시간 측정 (3회) ---"
for i in 1 2 3; do
    echo "시도 $i:"
    # KEYS 명령 시간 측정
    start_ns=$(date +%s%N)
    $REDIS_CLI KEYS "post:views:*" > /dev/null 2>&1
    end_ns=$(date +%s%N)
    elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
    echo "  KEYS post:views:* 소요시간: ${elapsed_ms}ms" | tee -a "$OUTPUT_DIR/keys-latency.txt"
done

echo ""
echo "--- KEYS prefix:* 소요시간 측정 (3회) ---"
for i in 1 2 3; do
    echo "시도 $i:"
    start_ns=$(date +%s%N)
    $REDIS_CLI KEYS "prefix:*" > /dev/null 2>&1
    end_ns=$(date +%s%N)
    elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
    echo "  KEYS prefix:* 소요시간: ${elapsed_ms}ms" | tee -a "$OUTPUT_DIR/keys-latency.txt"
done

# ──────────────────────────────────────
# Step 3: Baseline 레이턴시 (배치 없을 때)
# ──────────────────────────────────────
echo ""
echo "=== Step 3: Baseline 레이턴시 ==="

echo "--- redis-benchmark baseline GET (배치 없을 때) ---"
echo "(10만 요청, 50 클라이언트)"
docker exec wiki-redis-prod redis-benchmark -a "$REDIS_PASSWORD" --no-auth-warning \
    -t GET -n 100000 -c 50 -q 2>&1 | tee "$OUTPUT_DIR/benchmark-baseline.txt"

echo ""
echo "--- redis-benchmark baseline SET ---"
docker exec wiki-redis-prod redis-benchmark -a "$REDIS_PASSWORD" --no-auth-warning \
    -t SET -n 100000 -c 50 -q 2>&1 | tee -a "$OUTPUT_DIR/benchmark-baseline.txt"

echo ""
echo "--- redis-benchmark GET P50/P99/P999 상세 ---"
docker exec wiki-redis-prod redis-benchmark -a "$REDIS_PASSWORD" --no-auth-warning \
    -t GET -n 100000 -c 50 --csv 2>&1 | tee "$OUTPUT_DIR/benchmark-baseline-csv.txt"

# ──────────────────────────────────────
# Step 4: SLOWLOG 확인
# ──────────────────────────────────────
echo ""
echo "=== Step 4: SLOWLOG ==="

echo "--- slowlog-log-slower-than 설정 ---"
$REDIS_CLI CONFIG GET slowlog-log-slower-than

echo ""
echo "--- 최근 SLOWLOG (10개) ---"
$REDIS_CLI SLOWLOG GET 10 | tee "$OUTPUT_DIR/slowlog.txt"

# ──────────────────────────────────────
# Step 5: 블랙리스트 키 TTL 확인
# ──────────────────────────────────────
echo ""
echo "=== Step 5: 블랙리스트 키 TTL 확인 ==="

BLACKLIST_KEY=$($REDIS_CLI KEYS "blacklist:*" 2>/dev/null | head -1)
if [ -n "$BLACKLIST_KEY" ]; then
    echo "샘플 블랙리스트 키: $BLACKLIST_KEY"
    echo "TTL: $($REDIS_CLI TTL "$BLACKLIST_KEY")초"
    echo "→ volatile-lru 정책에서 eviction 대상 확인: TTL이 있으면 대상"
else
    echo "현재 블랙리스트 키 없음 (로그아웃한 사용자 없음)"
fi | tee "$OUTPUT_DIR/blacklist-ttl.txt"

# ──────────────────────────────────────
# 결과 요약
# ──────────────────────────────────────
echo ""
echo "============================================"
echo "Phase 15-0 Before 실측 완료"
echo "결과 파일: $OUTPUT_DIR/"
echo "============================================"
echo ""
echo "[캡처] 캡처할 것:"
echo "  1. 이 터미널 전체 출력"
echo "  2. $OUTPUT_DIR/ 의 각 파일"
echo ""
echo "다음 단계:"
echo "  - 배치 빌드 중 간섭 측정은 별도로 수행"
echo "    (buildPrefixTopK 실행 타이밍에 맞춰 redis-benchmark 동시 실행)"
echo "  - volatile-lru eviction 시뮬레이션은 별도로 수행"
echo "    (maxmemory 임시 축소 → 배치 빌드 → 블랙리스트 키 확인)"
