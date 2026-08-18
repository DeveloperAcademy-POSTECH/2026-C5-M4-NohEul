#!/usr/bin/env bash
set -euo pipefail

CAMPAIGN_ID="${1:?Usage: verify.sh <campaign_id> [base_url]}"
BASE_URL="${2:-http://localhost:8080}"
EXPECTED_TOTAL_QUANTITY=2000

RESPONSE=$(curl -sf "${BASE_URL}/api/coupons/${CAMPAIGN_ID}")

ISSUED=$(echo "$RESPONSE" | jq '.issuedQuantity')
REMAINING=$(echo "$RESPONSE" | jq '.remainingQuantity')
TOTAL=$(echo "$RESPONSE" | jq '.totalQuantity')

echo "campaign $CAMPAIGN_ID: total=$TOTAL issued=$ISSUED remaining=$REMAINING"

if [ "$TOTAL" -ne "$EXPECTED_TOTAL_QUANTITY" ]; then
  echo "FAIL: totalQuantity($TOTAL) != expected($EXPECTED_TOTAL_QUANTITY) - seed.sql이 제대로 실행됐는지 확인하세요"
  exit 1
fi

if [ "$ISSUED" -gt "$EXPECTED_TOTAL_QUANTITY" ]; then
  echo "FAIL: 과발급 발생 - issuedQuantity($ISSUED) > totalQuantity($EXPECTED_TOTAL_QUANTITY)"
  exit 1
fi

if [ "$REMAINING" -lt 0 ]; then
  echo "FAIL: remainingQuantity가 음수 - 데이터 정합성 깨짐"
  exit 1
fi

echo "PASS: 과발급 없음 (issued=$ISSUED, remaining=$REMAINING)"
if [ "$ISSUED" -lt "$EXPECTED_TOTAL_QUANTITY" ]; then
  echo "NOTE: 언더셀 발생 - $((EXPECTED_TOTAL_QUANTITY - ISSUED))장이 미발급 상태로 남음 (락 전략의 특성일 수 있음, 과발급은 아님)"
fi
