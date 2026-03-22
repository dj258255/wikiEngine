#!/bin/bash
# Phase 15-0 Step 4: volatile-lru eviction 시뮬레이션
# [주의] 주의: maxmemory를 임시로 축소합니다. 반드시 복원하세요.
# 사용법: bash phase15-eviction-test.sh

REDIS_PASSWORD="OF8A4z0bhIEQdyOlLoLAuiLwG3N7eO38czbhRIUR"
REDIS_CLI="docker exec wiki-redis-prod redis-cli -a $REDIS_PASSWORD --no-auth-warning"
OUTPUT_DIR="/tmp/phase15-before"
mkdir -p "$OUTPUT_DIR"

echo "============================================"
echo "Phase 15-0 Step 4: volatile-lru eviction 시뮬레이션"
echo "[주의]  이 스크립트는 maxmemory를 임시로 축소합니다"
echo "============================================"

# 현재 상태 기록
echo ""
echo "--- 현재 메모리 상태 ---"
USED=$($REDIS_CLI INFO memory | grep "used_memory:" | cut -d: -f2 | tr -d '\r')
USED_HUMAN=$($REDIS_CLI INFO memory | grep "used_memory_human:" | cut -d: -f2 | tr -d '\r')
MAXMEM=$($REDIS_CLI CONFIG GET maxmemory | tail -1 | tr -d '\r')
POLICY=$($REDIS_CLI CONFIG GET maxmemory-policy | tail -1 | tr -d '\r')
echo "used_memory: $USED ($USED_HUMAN)"
echo "maxmemory: $MAXMEM"
echo "maxmemory-policy: $POLICY"

# 블랙리스트 키 수 (Before)
echo ""
echo "--- 블랙리스트 키 수 (Before) ---"
BL_BEFORE=$($REDIS_CLI KEYS "blacklist:*" 2>/dev/null | wc -l | tr -d ' ')
echo "blacklist:* 키 수: $BL_BEFORE"

if [ "$BL_BEFORE" -eq "0" ]; then
    echo ""
    echo "[주의]  블랙리스트 키가 없습니다."
    echo "   테스트를 위해 먼저 로그인 → 로그아웃을 수행하여 블랙리스트 키를 생성하세요."
    echo "   예: curl -X POST https://api.studywithtymee.com/api/v1.0/auth/logout -H 'Authorization: Bearer <token>'"
    echo ""
    echo "블랙리스트 키 생성 후 이 스크립트를 다시 실행하세요."
    exit 1
fi

# 모든 키의 TTL 분포
echo ""
echo "--- TTL이 있는 키 수 (volatile-lru 대상) ---"
# 샘플 10개의 TTL 확인
echo "샘플 키 TTL:"
for key in $($REDIS_CLI KEYS "*" 2>/dev/null | head -10); do
    ttl=$($REDIS_CLI TTL "$key" 2>/dev/null)
    echo "  $key → TTL: ${ttl}s"
done

echo ""
echo "============================================"
echo "maxmemory를 (used_memory + 1MB)로 축소하겠습니까?"
echo "현재: used=$USED_HUMAN, maxmemory=$(echo "$MAXMEM / 1048576" | bc)MB"
TARGET=$(( USED + 1048576 ))  # used + 1MB
echo "변경: maxmemory → $(echo "$TARGET / 1048576" | bc)MB"
echo ""
read -p "진행하려면 'yes' 입력: " CONFIRM

if [ "$CONFIRM" != "yes" ]; then
    echo "취소됨."
    exit 0
fi

# maxmemory 축소
echo ""
echo "--- maxmemory 축소 ---"
$REDIS_CLI CONFIG SET maxmemory $TARGET
echo "maxmemory 변경 완료: $TARGET bytes ($(echo "$TARGET / 1048576" | bc)MB)"

# 추가 데이터 삽입하여 eviction 유발
echo ""
echo "--- eviction 유발을 위한 추가 데이터 삽입 ---"
for i in $(seq 1 500); do
    $REDIS_CLI SET "eviction-test:$i" "$(head -c 2048 /dev/urandom | base64)" EX 3600 > /dev/null 2>&1
done
echo "500개 테스트 키 삽입 완료 (각 ~2KB, TTL 1시간)"

# eviction 발생 확인
echo ""
echo "--- eviction 발생 확인 ---"
$REDIS_CLI INFO stats | grep evicted_keys

# 블랙리스트 키 수 (After)
echo ""
echo "--- 블랙리스트 키 수 (After eviction) ---"
BL_AFTER=$($REDIS_CLI KEYS "blacklist:*" 2>/dev/null | wc -l | tr -d ' ')
echo "blacklist:* 키 수: Before=$BL_BEFORE → After=$BL_AFTER"

if [ "$BL_AFTER" -lt "$BL_BEFORE" ]; then
    echo ""
    echo "[경고] 블랙리스트 키가 eviction 되었습니다!"
    echo "   → volatile-lru 정책에서 보안 키가 제거될 수 있음을 실증"
    echo "   → 로그아웃된 토큰이 다시 유효해지는 보안 사고 가능"
else
    echo ""
    echo "블랙리스트 키가 유지되었습니다 (다른 키가 먼저 eviction됨)"
    echo "   → 이번에는 LRU 순서상 다른 키가 먼저였지만,"
    echo "   → volatile-lru 정책에서 TTL 있는 블랙리스트 키는 여전히 eviction 대상"
fi

# [주의] 복원
echo ""
echo "--- [주의]  maxmemory 복원 ---"
$REDIS_CLI CONFIG SET maxmemory $MAXMEM
echo "maxmemory 복원 완료: $MAXMEM bytes"

# 테스트 키 정리
echo ""
echo "--- 테스트 키 정리 ---"
for i in $(seq 1 500); do
    $REDIS_CLI DEL "eviction-test:$i" > /dev/null 2>&1
done
echo "테스트 키 500개 삭제 완료"

echo ""
echo "--- 복원 후 메모리 상태 ---"
$REDIS_CLI INFO memory | grep -E "used_memory_human|maxmemory_human"

echo ""
echo "============================================"
echo "eviction 시뮬레이션 완료"
echo "[캡처] 이 터미널 전체 출력을 캡처하세요"
echo "============================================"
