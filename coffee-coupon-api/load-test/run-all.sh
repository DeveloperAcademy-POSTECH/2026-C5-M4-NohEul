#!/usr/bin/env bash
# 여러 전략을 같은 조건에서 연달아 부하 테스트한다: 전략마다 엔드포인트 확인 → 시딩 → k6 → verify.sh
# 사용법 (레포 루트에서, 앱을 먼저 띄워 둔 상태로):
#   coffee-coupon-api/load-test/run-all.sh                       # 모든 전략 (엔드포인트가 없는 전략은 건너뜀)
#   coffee-coupon-api/load-test/run-all.sh no-lock synchronized  # 고른 전략만
# k6 옵션(RATE, DURATION 등)은 환경 변수로 넘기면 그대로 전달된다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-root}"
DB_NAME="${DB_NAME:-coffee_coupon}"
LOCAL_YAML="$SCRIPT_DIR/../src/main/resources/application-local.yaml"

if [ "$#" -gt 0 ]; then
  STRATEGIES=("$@")
else
  STRATEGIES=(no-lock pessimistic optimistic synchronized distributed)
fi

# DB 비밀번호: MYSQL_PWD가 없으면 application-local.yaml에서 읽고(따옴표 제거), 그것도 없으면 docker-compose 기본값
if [ -z "${MYSQL_PWD:-}" ]; then
  if [ -f "$LOCAL_YAML" ]; then
    MYSQL_PWD=$(grep 'password:' "$LOCAL_YAML" | awk '{print $2}' | tr -d '"')
  else
    MYSQL_PWD=coffee
  fi
fi
export MYSQL_PWD DB_HOST DB_USER DB_NAME

# 앱이 떠 있는지 먼저 확인 (DB 연결까지 확인됨)
if ! curl -sf "$BASE_URL/actuator/health" > /dev/null; then
  echo "앱이 응답하지 않습니다. 먼저 ./gradlew bootRun 으로 띄워 주세요. ($BASE_URL)"
  exit 1
fi

# 엔드포인트가 있으면 없는 캠페인(0번)에 대해 우리 예외 응답(COUPON_NOT_FOUND)이 오고,
# 엔드포인트 자체가 없으면 스프링 기본 404가 온다. 이 차이로 미구현 전략을 걸러낸다.
endpoint_exists() {
  curl -s -X POST "$BASE_URL/api/coupons/0/issue-$1" \
    -H 'Content-Type: application/json' -d '{"userId": 0}' | grep -q 'COUPON_NOT_FOUND'
}

RESULT_DIR="$SCRIPT_DIR/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULT_DIR"
echo "결과 저장 위치: $RESULT_DIR"

SUMMARY="$RESULT_DIR/summary.md"
{
  echo "| 전략 | 성공(200) | 락 거부(403/409) | 인프라 오류 | dropped | 실제 발급 | 카운터 | 판정 | avg | p95 |"
  echo "|---|---|---|---|---|---|---|---|---|---|"
} > "$SUMMARY"

for STRATEGY in "${STRATEGIES[@]}"; do
  echo
  echo "===== $STRATEGY ====="

  if ! endpoint_exists "$STRATEGY"; then
    echo "issue-$STRATEGY 엔드포인트가 없어 건너뜁니다."
    echo "| $STRATEGY | 건너뜀 (엔드포인트 없음) | | | | | | | | |" >> "$SUMMARY"
    continue
  fi

  CAMPAIGN_ID=$(mysql -h "$DB_HOST" -u "$DB_USER" -D "$DB_NAME" -N < "$SCRIPT_DIR/seed.sql" | tail -1)
  echo "campaign_id=$CAMPAIGN_ID"

  K6_JSON="$RESULT_DIR/$STRATEGY-summary.json"
  k6 run --summary-export "$K6_JSON" \
    -e BASE_URL="$BASE_URL" -e CAMPAIGN_ID="$CAMPAIGN_ID" -e STRATEGY="$STRATEGY" \
    "$SCRIPT_DIR/coupon-issue-scale.js" 2>&1 | tee "$RESULT_DIR/$STRATEGY-k6.txt"

  # 과발급이면 verify.sh가 exit 1을 내지만, 나머지 전략도 계속 측정한다
  VERIFY_OUT="$RESULT_DIR/$STRATEGY-verify.txt"
  "$SCRIPT_DIR/verify.sh" "$CAMPAIGN_ID" "$BASE_URL" 2>&1 | tee "$VERIFY_OUT" || true

  metric() { jq -r "$1 // 0" "$K6_JSON"; }
  OK=$(metric '.metrics.result_issued.count')
  REJECTED=$(metric '.metrics.result_rejected.count')
  INFRA=$(metric '.metrics.result_infra_error.count')
  DROPPED=$(metric '.metrics.dropped_iterations.count')
  AVG=$(jq -r '.metrics.http_req_duration.avg | floor' "$K6_JSON")
  P95=$(jq -r '.metrics.http_req_duration["p(95)"] | floor' "$K6_JSON")
  ACTUAL=$(sed -n 's/.*actual_issued(coupon_issue)=\([0-9]*\).*/\1/p' "$VERIFY_OUT")
  COUNTER=$(sed -n 's/.*counter_issued(issuedQuantity)=\([0-9]*\).*/\1/p' "$VERIFY_OUT")
  if grep -q '^PASS' "$VERIFY_OUT"; then VERDICT=PASS; else VERDICT='**FAIL(과발급)**'; fi

  echo "| $STRATEGY | $OK | $REJECTED | $INFRA | $DROPPED | $ACTUAL | $COUNTER | $VERDICT | ${AVG}ms | ${P95}ms |" >> "$SUMMARY"
done

echo
echo "===== 결과 요약 (재고 2,000장, RATE=${RATE:-500}/s, DURATION=${DURATION:-10s}) ====="
cat "$SUMMARY"
echo
echo "표는 $SUMMARY 에도 저장했습니다."
