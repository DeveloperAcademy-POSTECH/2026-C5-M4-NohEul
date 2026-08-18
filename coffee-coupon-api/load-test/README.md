# 락 전략 비교 부하테스트 하네스

락 없음(대조군)/비관적/낙관적/분산 락 네 가지가 가정한 트래픽(peak 6,000 QPS, 재고 2,000장, 10초 몰림)에서
과발급 없이 동작하는지(또는 얼마나 과발급되는지) 동일한 기준으로 검증하는 도구 세트입니다.
설계 배경은 `docs/superpowers/specs/2026-08-17-lock-strategy-load-test-harness-design.md` 참고.

### 왜 6,000 QPS인가

알림 대상 30만 명 · 재고 2,000장 · 실제 시도율 20%(6만 명) · 오픈 직후 몰림 10초를 가정해서 역산한 값입니다. 근거는 위 스펙 문서 참고.

## 사전 준비

- k6 (`brew install k6`)
- jq (`brew install jq`)
- mysql CLI
- 로컬 MySQL이 떠 있고 `coffee-coupon-api/src/main/resources/application-local.yaml`이 설정돼 있을 것 (README 루트의 설정 방법 참고)
- 네 전략이 `/api/coupons/{campaignId}/issue-<전략>` 형태의 별도 엔드포인트(`issue-no-lock`/`issue-pessimistic`/`issue-optimistic`/`issue-distributed`)로 앱에 존재해야 한다. 각 전략은 자기 브랜치에서 구현되지만 브랜치가 순차적으로 develop에 머지되므로(구현→머지 반복), 가장 최근에 머지된 브랜치에는 그때까지의 엔드포인트가 전부 함께 있다.

## 전략 하나를 검증하는 순서

브랜치를 오갈 필요 없이, **엔드포인트가 다 모여 있는 브랜치 하나에서 앱을 한 번만 띄워두고** 전략(엔드포인트)만 바꿔가며 반복하면 된다.

1. 앱을 기동한다 (별도 터미널, 계속 띄워둠).
   ```bash
   cd coffee-coupon-api
   ./gradlew bootRun
   ```
2. 검증할 전략을 고른다. **`no-lock`(대조군)부터 먼저 하는 걸 권장** — 락이 없을 때 실제로 얼마나 과발급되는지 먼저 확인해두면 나머지 세 전략의 결과를 해석할 기준이 생긴다.
   ```bash
   STRATEGY=no-lock   # 이후 pessimistic → optimistic → distributed 순으로 반복
   ```
3. 캠페인을 새로 시딩하고 `campaign_id`를 확보한다. **전략마다 매번 새로 시딩한다** — 이전 전략이 다 써버린 캠페인을 재사용하면 안 된다.
   ```bash
   MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon < load-test/seed.sql
   ```
4. 부하를 발사한다 (기본값 6,000 req/s × 10초 = 총 60,000건).
   ```bash
   k6 run -e CAMPAIGN_ID=<3단계에서 나온 값> -e STRATEGY=$STRATEGY coffee-coupon-api/load-test/coupon-issue-scale.js
   ```
   작게 먼저 확인하고 싶으면 `-e RATE=10 -e DURATION=2s -e PRE_ALLOCATED_VUS=10 -e MAX_VUS=20`으로 축소해서 실행.
5. 결과를 검증한다.
   ```bash
   coffee-coupon-api/load-test/verify.sh <campaign_id>
   ```
6. 2~5번을 `STRATEGY`만 바꿔서 반복한다 (no-lock → pessimistic → optimistic → distributed). 아래 표 형식으로 결과를 기록한다. 성공/실패 카운트는 k6 요약의 상태코드 분포에서 확인한다. **5xx(커넥션 타임아웃 등 인프라 오류)는 403/409(락 로직에 의한 정상 거부)와 반드시 분리해서 별도 칸에 적는다** — 섞으면 "락이 막은 것"과 "인프라가 못 버틴 것"을 구분할 수 없다.

| 락 전략 | 성공(200) | 락에 의한 실패(403/409) | 인프라 오류(5xx/dropped) | 최종 issuedQuantity | 과발급 | 비고(처리량/p95 등) |
|---|---|---|---|---|---|---|
| **락 없음 (대조군)** | | | | | | |
| 비관적 락 | | | | | | |
| 낙관적 락 | | | | | | |
| 분산 락 | | | | | | |

## 결과 해석 시 주의

- **DB 커넥션 풀**: `application-local.yaml`의 Hikari 풀 크기가 네 가지 모두에 동일하게 적용되는 조건이다. 실측 처리량이 6,000 req/s에 못 미쳐도 그게 "락 자체의 한계"인지 "커넥션 풀 크기의 한계"인지는 이 표만으로는 구분 안 된다. 절대 수치보다 **네 가지 간 상대 비교**에 집중한다.
- **낙관적 락의 언더셀**: 재시도 로직 없이 구현했다면 `issuedQuantity`가 2,000에 못 미치는 채로 끝날 수 있다. 이건 과발급이 아니라 낙관적 락의 특성이므로 "과발급" 칸에는 "없음"으로 적고, 비고에 언더셀 수치를 남긴다.
- **처리량/지연시간은 판정에 안 쓴다**: k6 요약의 처리량(req/s)·p95 등은 비고 칸에 참고용으로 기록만 한다. 이번 하네스의 PASS/FAIL은 정합성(과발급 여부)만으로 결정한다.

## 문제 해결

- `verify.sh`가 `FAIL: totalQuantity(...) != expected(2000)`을 내면 `seed.sql`이 제대로 안 돌았거나 다른 campaign_id를 잘못 넣은 것이다.
- k6가 `dropped_iterations`를 많이 보고하면 `MAX_VUS`가 부족해서 목표 QPS를 못 낸 것이다 — 이 자체도 그 락 전략이 해당 QPS를 못 버틴다는 신호이니 위 표의 "인프라 오류" 칸에 기록한다.
- k6 요약에 404가 대량으로 찍히면 `STRATEGY` 값에 오타가 있거나(`no-lock`/`pessimistic`/`optimistic`/`distributed` 중 하나여야 함), 그 전략의 엔드포인트를 추가한 브랜치가 아직 지금 체크아웃한 브랜치에 안 들어와 있는 것이다.
