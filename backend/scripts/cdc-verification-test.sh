#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# CDC 검증 테스트 — Phase 14-3 핵심 이점 실증
#
# 구멍 1: 직접 SQL DELETE → CDC 감지 → Lucene 반영 검증
# 구멍 2: CDC end-to-end 지연 측정 (게시글 생성 → 검색 노출까지)
#
# 사전 조건:
#   - 서버에 SSH 접속 가능
#   - API 서버 동작 중
#   - Kafka + Debezium CDC 파이프라인 동작 중
#
# 사용법:
#   export API_URL="https://api.studywithtymee.com"
#   export SSH_HOST="서버1_IP"
#   export SSH_USER="ubuntu"
#   export MYSQL_CONTAINER="wiki-mysql-prod"
#   export MYSQL_ROOT_PW="your_root_password"
#   export DB_NAME="wikidb"
#
#   bash backend/scripts/cdc-verification-test.sh
#
# 출력: backend/scripts/cdc-test-results/ 에 저장됨
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail

# ─── 설정 ────────────────────────────────────────────────
API_URL="${API_URL:?'API_URL 환경변수를 설정하세요 (예: https://api.studywithtymee.com)'}"
SSH_HOST="${SSH_HOST:?'SSH_HOST 환경변수를 설정하세요 (MySQL Primary 서버 IP)'}"
SSH_USER="${SSH_USER:-rocky}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/oci_key}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-wiki-mysql-prod}"
MYSQL_ROOT_PW="${MYSQL_ROOT_PW:?'MYSQL_ROOT_PW 환경변수를 설정하세요'}"
DB_NAME="${DB_NAME:-wikidb}"

API_PREFIX="${API_URL}/api/v1.0"
RESULTS_DIR="$(dirname "$0")/cdc-test-results"
mkdir -p "$RESULTS_DIR"

TEST_USER="k6user1"
TEST_PASSWORD="Test1234!"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# ─── 유틸 함수 ───────────────────────────────────────────

log() { echo "[$(date '+%H:%M:%S.%3N')] $*"; }

login() {
    local cookie_file="$RESULTS_DIR/.cookies"
    curl -s -c "$cookie_file" -X POST "$API_PREFIX/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$TEST_USER\",\"password\":\"$TEST_PASSWORD\"}" \
        -o /dev/null
    echo "$cookie_file"
}

create_post() {
    local cookie_file="$1"
    local title="$2"
    local response
    response=$(curl -s -b "$cookie_file" -X POST "$API_PREFIX/posts" \
        -H "Content-Type: application/json" \
        -d "{\"title\":\"$title\",\"content\":\"CDC 테스트용 게시글입니다. $(date)\"}")
    echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null
}

search_contains_id() {
    local query="$1"
    local target_id="$2"
    local encoded_query
    encoded_query=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$query'))")
    # Nginx 로드밸런싱으로 App 1 / App 2 중 하나에 갈 수 있으므로
    # 3번 시도하여 한 번이라도 발견되면 true (App 2의 Lucene에만 있을 수 있음)
    for attempt in 1 2 3; do
        local response
        response=$(curl -s "$API_PREFIX/posts/search?q=${encoded_query}&page=0&size=50")
        local found
        found=$(echo "$response" | python3 -c "
import sys, json
data = json.load(sys.stdin)
posts = data.get('data', {}).get('content', [])
found = any(p.get('id') == $target_id for p in posts)
print('true' if found else 'false')
" 2>/dev/null)
        if [ "$found" = "true" ]; then
            echo "true"
            return
        fi
    done
    echo "false"
}

mysql_direct() {
    local sql="$1"
    ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$SSH_HOST" \
        "docker exec $MYSQL_CONTAINER mysql -uroot -p'$MYSQL_ROOT_PW' $DB_NAME -e \"$sql\"" 2>/dev/null
}

now_ms() {
    python3 -c "import time; print(time.time())"
}

# ═══════════════════════════════════════════════════════════
# 테스트 1: 직접 SQL DELETE → CDC 감지 → Lucene 반영 검증
# ═══════════════════════════════════════════════════════════

test_cdc_correctness() {
    log "═══════════════════════════════════════════════════"
    log "테스트 1: CDC 정확성 검증 — 직접 SQL DELETE"
    log "  시나리오: PostService를 우회하여 MySQL에서 직접 삭제"
    log "  기대 결과: Debezium이 binlog에서 DELETE를 감지 → Lucene에서 자동 삭제"
    log "═══════════════════════════════════════════════════"

    local cookie_file
    cookie_file=$(login)
    log "로그인 완료"

    # 1. 고유한 제목으로 게시글 생성 (Lucene Nori 토크나이저가 토큰화할 수 있도록)
    local unique_title="CDC검증 ${TIMESTAMP} 정확성테스트"
    local post_id
    post_id=$(create_post "$cookie_file" "$unique_title")

    if [ -z "$post_id" ]; then
        log "ERROR: 게시글 생성 실패"
        return 1
    fi
    log "Step 1) 게시글 생성 완료: id=$post_id, title=$unique_title"

    # 2. 검색에 노출될 때까지 대기
    log "Step 2) 검색 노출 대기 중..."
    local max_wait=60
    local found="false"
    for i in $(seq 1 $max_wait); do
        found=$(search_contains_id "$unique_title" "$post_id")
        if [ "$found" = "true" ]; then
            log "  → 검색 노출 확인 (${i}초 후)"
            break
        fi
        sleep 1
    done

    if [ "$found" != "true" ]; then
        log "WARNING: ${max_wait}초 내에 검색에 노출되지 않음"
        log "  → CDC 파이프라인 상태 확인 필요"
        return 1
    fi

    # 3. 직접 SQL로 DELETE (PostService 완전 우회)
    log "Step 3) 직접 SQL DELETE 실행 (PostService 우회)"
    log "  → DELETE FROM posts WHERE id = $post_id"
    mysql_direct "DELETE FROM posts WHERE id = $post_id;"
    local t_delete
    t_delete=$(now_ms)
    log "  → MySQL 직접 삭제 완료"

    # 4. API 상세 조회로 DB 삭제 확인
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" "$API_PREFIX/posts/$post_id")
    log "Step 4) API 상세 조회: HTTP $http_code"

    # 5. CDC → Lucene 삭제 반영 확인 (폴링)
    log "Step 5) CDC → Lucene 삭제 반영 대기 중..."
    local deleted_from_search="false"
    local cdc_delete_latency=""

    for i in $(seq 1 60); do
        found=$(search_contains_id "$unique_title" "$post_id")
        if [ "$found" = "false" ]; then
            local t_confirmed
            t_confirmed=$(now_ms)
            cdc_delete_latency=$(python3 -c "print(f'{($t_confirmed - $t_delete):.2f}')")
            deleted_from_search="true"
            log "  → 검색에서 삭제 확인! (SQL DELETE → Lucene 반영: ${cdc_delete_latency}초)"
            break
        fi
        sleep 1
    done

    # 6. 결과 기록
    local result_file="$RESULTS_DIR/test1_cdc_correctness_${TIMESTAMP}.txt"
    {
        echo "═══ CDC 정확성 테스트 결과 ═══"
        echo "테스트 일시: $(date)"
        echo "게시글 ID: $post_id"
        echo "제목: $unique_title"
        echo ""
        echo "시나리오: PostService를 거치지 않는 직접 SQL DELETE"
        echo "  1) 게시글 생성 (API): 성공 (id=$post_id)"
        echo "  2) 검색 노출 확인: 성공"
        echo "  3) MySQL 직접 삭제: DELETE FROM posts WHERE id = $post_id"
        echo "  4) API 상세 조회: HTTP $http_code"
        if [ "$deleted_from_search" = "true" ]; then
            echo "  5) CDC → Lucene 삭제 반영: 성공 (${cdc_delete_latency}초)"
            echo ""
            echo "결론: Debezium이 PostService를 우회한 직접 SQL DELETE를"
            echo "       binlog에서 감지하여 Lucene 인덱스에서 자동 삭제했다."
            echo "       @ApplicationModuleListener에서는 이 경로가 감지되지 않는다."
            echo "       이것이 CDC 도입의 핵심 이점이다."
        else
            echo "  5) CDC → Lucene 삭제 반영: 60초 내 미반영"
            echo ""
            echo "결론: CDC 파이프라인 확인 필요"
            echo "  → Debezium Connector 상태: curl http://서버2:8083/connectors/wiki-mysql-connector/status"
            echo "  → Consumer 로그: docker logs wiki-app-prod --tail 50"
        fi
    } > "$result_file"

    log "결과 저장: $result_file"
    echo ""
    cat "$result_file"
    echo ""
}

# ═══════════════════════════════════════════════════════════
# 테스트 2: CDC end-to-end 지연 측정
# ═══════════════════════════════════════════════════════════

test_cdc_latency() {
    log "═══════════════════════════════════════════════════"
    log "테스트 2: CDC end-to-end 지연 측정"
    log "  경로: POST API → MySQL → binlog → Debezium → Kafka → Consumer → Lucene"
    log "  측정: 게시글 생성 API 호출 → 검색에 처음 노출되기까지"
    log "  반복: 10회"
    log "═══════════════════════════════════════════════════"

    local cookie_file
    cookie_file=$(login)
    log "로그인 완료"

    local iterations=10
    local latency_values=""
    local success_count=0
    local result_file="$RESULTS_DIR/test2_cdc_latency_${TIMESTAMP}.txt"

    {
        echo "═══ CDC End-to-End 지연 측정 결과 ═══"
        echo "테스트 일시: $(date)"
        echo "반복 횟수: $iterations"
        echo "경로: POST API → MySQL → binlog → Debezium → Kafka → Consumer → Lucene → 검색 API"
        echo "폴링 간격: 500ms"
        echo ""
    } > "$result_file"

    for run in $(seq 1 $iterations); do
        local unique_title="CDC지연측정 ${TIMESTAMP} ${run}회차"

        local t0
        t0=$(now_ms)

        local post_id
        post_id=$(create_post "$cookie_file" "$unique_title")

        if [ -z "$post_id" ]; then
            log "  [$run/$iterations] ERROR: 게시글 생성 실패"
            echo "  Run $run: FAILED (게시글 생성 실패)" >> "$result_file"
            continue
        fi

        # 0.5초 간격 폴링 (최대 60초)
        local found="false"
        local t1=""
        for poll in $(seq 1 120); do
            found=$(search_contains_id "$unique_title" "$post_id")
            if [ "$found" = "true" ]; then
                t1=$(now_ms)
                break
            fi
            sleep 0.5
        done

        if [ "$found" = "true" ] && [ -n "$t1" ]; then
            local latency_ms
            latency_ms=$(python3 -c "print(int(($t1 - $t0) * 1000))")
            if [ -n "$latency_values" ]; then
                latency_values="$latency_values,$latency_ms"
            else
                latency_values="$latency_ms"
            fi
            success_count=$((success_count + 1))
            log "  [$run/$iterations] id=$post_id → 검색 노출: ${latency_ms}ms"
            echo "  Run $run: id=$post_id, latency=${latency_ms}ms" >> "$result_file"
        else
            log "  [$run/$iterations] id=$post_id → 60초 내 미노출"
            echo "  Run $run: id=$post_id, TIMEOUT (>60s)" >> "$result_file"
        fi

        sleep 2
    done

    # 통계 계산
    if [ "$success_count" -gt 0 ]; then
        python3 -c "
latencies = sorted([$latency_values])
n = len(latencies)
avg = sum(latencies) / n
p50 = latencies[n // 2]
p95_idx = int(n * 0.95)
p95 = latencies[min(p95_idx, n - 1)]
mn = latencies[0]
mx = latencies[-1]

summary = f'''
═══ 통계 ═══
  성공: {n} / $iterations
  평균: {avg:.0f}ms
  P50:  {p50}ms
  P95:  {p95}ms
  최소: {mn}ms
  최대: {mx}ms

참고: Phase 14-1b(@ApplicationModuleListener)에서는 같은 JVM 내에서
      비동기로 ~100ms 내에 Lucene을 갱신했다. CDC 경로는 binlog →
      Debezium → Kafka → Consumer → Lucene을 거치므로 더 느리다.
      그러나 CDC의 이점(직접 SQL 감지, 이벤트 리플레이, 앱 외부 독립 동작)은
      이 지연 증가를 감수할 가치가 있다.
'''
print(summary)
with open('$result_file', 'a') as f:
    f.write(summary)
"
        log "  평균: $(python3 -c "l=[$latency_values]; print(f'{sum(l)/len(l):.0f}ms')")  성공: $success_count/$iterations"
    else
        echo "" >> "$result_file"
        echo "모든 반복 실패 — CDC 파이프라인 상태를 확인하세요." >> "$result_file"
        log "ERROR: 모든 반복 실패"
    fi

    log "결과 저장: $result_file"

    # 테스트 게시글 정리
    log ""
    log "테스트 게시글 정리 중 (직접 SQL DELETE — CDC가 Lucene에서도 자동 삭제)..."
    mysql_direct "DELETE FROM posts WHERE title LIKE 'CDC지연측정 ${TIMESTAMP}%';"
    log "정리 완료"
}

# ═══════════════════════════════════════════════════════════
# 메인 실행
# ═══════════════════════════════════════════════════════════

log "CDC 검증 테스트 시작"
log "  API: $API_URL"
log "  SSH: $SSH_USER@$SSH_HOST"
log "  MySQL: $MYSQL_CONTAINER @ $DB_NAME"
log ""

# 연결 확인
if ! curl -s -o /dev/null "$API_PREFIX/posts?page=0&size=1" --max-time 10; then
    log "ERROR: API 서버 연결 실패: $API_URL"
    exit 1
fi
log "API 서버 연결 확인"

if ! ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 -i "$SSH_KEY" "$SSH_USER@$SSH_HOST" "echo ok" > /dev/null 2>&1; then
    log "ERROR: SSH 연결 실패: $SSH_USER@$SSH_HOST"
    exit 1
fi
log "SSH 연결 확인"
log ""

test_cdc_correctness
echo ""
test_cdc_latency

log ""
log "═══ 전체 테스트 완료 ═══"
log "결과 디렉토리: $RESULTS_DIR"
ls -la "$RESULTS_DIR"/test*_${TIMESTAMP}*.txt 2>/dev/null || true
