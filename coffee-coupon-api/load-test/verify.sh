#!/usr/bin/env bash
set -euo pipefail

CAMPAIGN_ID="${1:?Usage: verify.sh <campaign_id> [base_url]}"
BASE_URL="${2:-http://localhost:8080}"
# DB 접속 기본값은 저장소 루트의 .env(docker-compose와 같은 파일)에서 읽는다
source "$(dirname "$0")/db-env.sh"
EXPECTED_TOTAL_QUANTITY=2000

if ! RESPONSE=$(curl -sf "${BASE_URL}/api/coupons/${CAMPAIGN_ID}"); then
  echo "FAIL: 앱에서 campaign ${CAMPAIGN_ID}을 조회하지 못했습니다. 앱이 떠 있는지, 앱과 mysql이 같은 DB를 보고 있는지 확인하세요."
  exit 1
fi

COUNTER_ISSUED=$(echo "$RESPONSE" | jq '.issuedQuantity')
TOTAL=$(echo "$RESPONSE" | jq '.totalQuantity')

# campaign.issuedQuantity는 동시 요청 시 Lost Update로 실제 발급 건수를 과소집계할 수 있다
# (읽은 시점의 스냅샷에 +1 해서 저장하므로, 두 요청이 같은 값을 읽으면 카운터는 1만 늘어도
# coupon_issue 행은 둘 다 insert된다). 그래서 판정은 반드시 coupon_issue 실제 행 수로 한다.
ACTUAL_ISSUED=$(mysql -h "$DB_HOST" -u "$DB_USER" -D "$DB_NAME" -N -e \
  "SELECT COUNT(*) FROM coupon_issue WHERE coupon_campaign_id = ${CAMPAIGN_ID};")

echo "campaign $CAMPAIGN_ID: total=$TOTAL actual_issued(coupon_issue)=$ACTUAL_ISSUED counter_issued(issuedQuantity)=$COUNTER_ISSUED"

if [ "$TOTAL" -ne "$EXPECTED_TOTAL_QUANTITY" ]; then
  echo "FAIL: totalQuantity($TOTAL) != expected($EXPECTED_TOTAL_QUANTITY) - seed.sql이 제대로 실행됐는지 확인하세요"
  exit 1
fi

if [ "$ACTUAL_ISSUED" -gt "$EXPECTED_TOTAL_QUANTITY" ]; then
  echo "FAIL: 과발급 발생 - coupon_issue 실제 행 수($ACTUAL_ISSUED) > totalQuantity($EXPECTED_TOTAL_QUANTITY)"
  exit 1
fi

if [ "$ACTUAL_ISSUED" -ne "$COUNTER_ISSUED" ]; then
  echo "NOTE: campaign.issuedQuantity 카운터($COUNTER_ISSUED)가 실제 발급 행 수($ACTUAL_ISSUED)와 다릅니다 - Lost Update 발생 (이번엔 과발급으로 안 이어졌어도 카운터 자체의 정합성은 깨진 상태)"
fi

echo "PASS: 과발급 없음 (actual_issued=$ACTUAL_ISSUED, remaining=$((EXPECTED_TOTAL_QUANTITY - ACTUAL_ISSUED)))"
if [ "$ACTUAL_ISSUED" -lt "$EXPECTED_TOTAL_QUANTITY" ]; then
  echo "NOTE: 언더셀 발생 - $((EXPECTED_TOTAL_QUANTITY - ACTUAL_ISSUED))장이 미발급 상태로 남음 (락 전략의 특성일 수 있음, 과발급은 아님)"
fi
