# 락 전략 비교용 k6 부하테스트 하네스 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 락 없음(대조군)/비관적/낙관적/분산 락 네 가지를 동일한 기준(peak 6,000 QPS, 재고 2,000장)으로 반복 검증할 수 있는 k6 기반 부하테스트 하네스(seed.sql + coupon-issue-scale.js + verify.sh + README.md)를 만든다.

**Architecture:** Kotlin 소스 트리와 분리된 `coffee-coupon-api/load-test/` 디렉토리에 4개 파일을 만든다. SQL로 캠페인을 직접 시딩(생성 API가 없으므로) → k6로 6,000 req/s × 10초 부하를 실제 실행 중인 API에 발사 → 최종 재고를 조회해 과발급 여부를 검증하는 3단계 파이프라인이다. 자동화된 CI 게이트가 아니라 사람이 브랜치별로 따라 실행하는 러너북이다.

**Tech Stack:** k6 v1.1.0 (`constant-arrival-rate` executor), MySQL 9.x (mysql CLI), bash, jq, curl — 모두 로컬에 설치 확인됨. Gradle/Kotlin 빌드와 무관.

**Spec:** `docs/superpowers/specs/2026-08-17-lock-strategy-load-test-harness-design.md`

## Global Constraints

- 판정 기준은 **정합성(과발급 여부)만**. 처리량/지연시간은 k6가 기본으로 남기는 걸 기록만 하고 이번 하네스의 PASS/FAIL 판정에는 쓰지 않는다.
- 부하 프로파일 기본값: `rate=6000`(req/s), `duration=10s`, `preAllocatedVUs=2000`, `maxVUs=8000` — 모두 k6 스크립트에서 `__ENV` 오버라이드 가능해야 한다(스케일다운 드라이런 용도).
- 캠페인 생성 API가 프로젝트에 없으므로, 시딩은 **SQL 직접 insert**(`load-test/seed.sql`)로 한다. 매번 새 `CouponTemplate`/`CouponCampaign` 행을 insert하고 생성된 `campaign_id`를 stdout에 출력해야 한다(`SELECT LAST_INSERT_ID()`).
- `CouponIssue`에는 `(coupon_campaign_id, user_id)` 유니크 제약이 있다. k6 스크립트는 `exec.scenario.iterationInTest`로 전역 유일한 `userId`를 생성해야 한다(같은 userId 중복 시 "재고 소진"이 아니라 "중복 발급"으로 판정이 오염됨).
- DB 접속 정보(사용자/비밀번호)는 **어떤 커밋 파일에도 하드코딩하지 않는다** — README에서 환경변수나 CLI 인자로 넘기도록 안내한다(이 프로젝트가 `application-local.yaml`을 gitignore로 뺀 것과 같은 이유).
- 시딩되는 캠페인은 `total_quantity=2000`, `open_at`은 항상 과거 시각(예: `NOW() - INTERVAL 1분`)으로 고정한다 — 오픈 시각 게이팅 자체의 재현은 이 하네스의 범위 밖이다.
- 비교 대상은 **락 없음(대조군) → 비관적 락 → 낙관적 락 → 분산 락** 네 가지다. 각 전략은 **자기 브랜치에서 별도 엔드포인트**(`/api/coupons/{campaignId}/issue-no-lock`, `-pessimistic`, `-optimistic`, `-distributed`)로 구현되고, 브랜치가 순차적으로 develop에 머지되므로(구현→머지 반복) 엔드포인트가 누적된다. 기존에 이미 병합된 비관적 락은 접미사 없는 `/issue`를 그대로 쓰고 있어서, `/issue-pessimistic`으로 이름을 맞추고 `/issue-no-lock`을 새로 추가하는 작업이 선행되어야 한다 — **이 작업은 이 계획의 범위 밖**이다(낙관적/분산 락 엔드포인트 추가와 마찬가지로 별도 브랜치/작업). 이 계획은 어떤 시점에 몇 개의 엔드포인트가 존재하든 재사용할 수 있는 하네스만 만든다.
- k6 스크립트는 `STRATEGY` 환경변수(`no-lock`/`pessimistic`/`optimistic`/`distributed`)를 받아 `/api/coupons/{campaignId}/issue-${STRATEGY}`를 호출해야 한다.
- 파일 위치는 전부 `coffee-coupon-api/load-test/` 아래.

---

### Task 1: `seed.sql` — 캠페인 시딩 스크립트

**Files:**
- Create: `coffee-coupon-api/load-test/seed.sql`

**Interfaces:**
- Produces: `coupon_template` 1행 + `coupon_campaign` 1행(`total_quantity=2000`, `issued_quantity=0`, `open_at`=과거)을 insert하고, 표준출력에 컬럼명 `campaign_id`로 새로 생성된 `coupon_campaign.id`를 출력한다. Task 2(k6 스크립트)와 Task 3(verify.sh)이 이 `campaign_id` 값을 입력으로 받는다.

이 태스크는 순수 SQL 스크립트라 TDD 사이클이 자연스럽지 않다 — 로컬 MySQL에 실제로 두 번 실행해서 서로 다른 `campaign_id`가 나오는지, 값이 기대한 대로 들어갔는지 확인하는 것으로 검증한다.

- [ ] **Step 1: `seed.sql` 작성**

```sql
INSERT INTO coupon_template (name, discount_rate) VALUES ('부하테스트용 아메리카노 10% 할인', 10);
SET @template_id = LAST_INSERT_ID();
INSERT INTO coupon_campaign (coupon_template_id, total_quantity, issued_quantity, open_at)
VALUES (@template_id, 2000, 0, DATE_SUB(NOW(), INTERVAL 1 MINUTE));
SELECT LAST_INSERT_ID() AS campaign_id;
```

- [ ] **Step 2: 로컬 MySQL에 두 번 연속 실행해서 검증**

앱을 띄우지 않고 `coffee-coupon-api/src/main/resources/application-local.yaml`에 적힌 접속 정보로 직접 실행한다(비밀번호는 셸 히스토리에 남지 않게 `MYSQL_PWD` 환경변수 사용을 권장).

Run:
```bash
cd coffee-coupon-api
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon < load-test/seed.sql
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon < load-test/seed.sql
```

Expected: 첫 번째 실행은 `campaign_id 1`(또는 기존 최대값+1), 두 번째 실행은 그보다 1 큰 값을 출력한다. 두 값이 달라야 한다(같으면 `LAST_INSERT_ID()` 사용법이 잘못된 것).

- [ ] **Step 3: 값 검증**

Run:
```bash
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon -e \
  "SELECT id, coupon_template_id, total_quantity, issued_quantity, open_at < NOW() AS is_open FROM coupon_campaign ORDER BY id DESC LIMIT 2;"
```

Expected: 방금 만든 두 행 모두 `total_quantity=2000`, `issued_quantity=0`, `is_open=1`.

- [ ] **Step 4: 테스트로 만든 행 정리**

Run:
```bash
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon -e \
  "DELETE FROM coupon_campaign WHERE total_quantity = 2000 AND issued_quantity = 0 AND coupon_template_id IN (SELECT id FROM coupon_template WHERE name = '부하테스트용 아메리카노 10% 할인'); DELETE FROM coupon_template WHERE name = '부하테스트용 아메리카노 10% 할인';"
```

Expected: 개발 DB에 검증용으로 남긴 임시 행이 사라진다(이후 태스크에서 다시 seed.sql로 만들 것이므로 여기선 흔적만 지운다).

- [ ] **Step 5: 커밋**

```bash
git add coffee-coupon-api/load-test/seed.sql
git commit -m "feat: add campaign seed script for lock-strategy load test"
```

---

### Task 2: `coupon-issue-scale.js` — k6 부하 스크립트

**Files:**
- Create: `coffee-coupon-api/load-test/coupon-issue-scale.js`

**Interfaces:**
- Consumes: Task 1이 만든 `campaign_id`(환경변수 `CAMPAIGN_ID`로 전달받음), 환경변수 `STRATEGY`(`no-lock`/`pessimistic`/`optimistic`/`distributed` 중 하나), `POST /api/coupons/{campaignId}/issue-{STRATEGY}`(body: `{ "userId": Long }`, 성공 200/오픈전 403/소진·중복 409를 반환). 이 네 엔드포인트는 각 락 전략 브랜치가 순차적으로 추가하는 것으로, 이 태스크 시점엔 아직 하나도 존재하지 않을 수 있다(Global Constraints 참고).
- Produces: 실행 시 k6 표준 요약(상태코드별 카운트, 처리량, 지연시간)을 stdout에 출력. Task 3(`verify.sh`)은 이 스크립트가 만든 발급 결과를 API로 재조회해서 검증한다(직접적인 파일/함수 의존은 없음).

- [ ] **Step 1: `coupon-issue-scale.js` 작성**

```javascript
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID;
const STRATEGY = __ENV.STRATEGY;
const VALID_STRATEGIES = ['no-lock', 'pessimistic', 'optimistic', 'distributed'];
const RATE = Number(__ENV.RATE || 6000);
const DURATION = __ENV.DURATION || '10s';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 2000);
const MAX_VUS = Number(__ENV.MAX_VUS || 8000);

if (!CAMPAIGN_ID) {
  throw new Error('CAMPAIGN_ID env var is required. Usage: k6 run -e CAMPAIGN_ID=42 -e STRATEGY=pessimistic coupon-issue-scale.js');
}
if (!VALID_STRATEGIES.includes(STRATEGY)) {
  throw new Error(`STRATEGY env var must be one of ${VALID_STRATEGIES.join(', ')}, got: ${STRATEGY}`);
}

export const options = {
  scenarios: {
    coupon_open_burst: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
};

export default function () {
  const userId = exec.scenario.iterationInTest;
  const url = `${BASE_URL}/api/coupons/${CAMPAIGN_ID}/issue-${STRATEGY}`;
  const payload = JSON.stringify({ userId });
  const params = { headers: { 'Content-Type': 'application/json' } };

  const res = http.post(url, payload, params);

  check(res, {
    'status is 200, 403, or 409': (r) => [200, 403, 409].includes(r.status),
  });
}
```

- [ ] **Step 2: 앱 없이 스크립트 문법만 스모크 테스트**

Run:
```bash
k6 run -e CAMPAIGN_ID=1 -e STRATEGY=pessimistic -e RATE=10 -e DURATION=2s -e PRE_ALLOCATED_VUS=10 -e MAX_VUS=20 coffee-coupon-api/load-test/coupon-issue-scale.js
```

Expected: 앱이 안 떠 있어서 모든 요청이 `connection refused`로 실패하고 `checks_failed: 100%`가 뜨지만, k6 자체는 파싱/런타임 에러 없이 "iterations: 20" 근처(10 req/s × 2s)로 완주해야 한다. 여기서 확인하려는 건 스크립트 문법이지 실제 응답이 아니다.

`STRATEGY`를 빼고 실행하면(`k6 run -e CAMPAIGN_ID=1 ... coupon-issue-scale.js`) `STRATEGY env var must be one of ...` 에러로 바로 죽는지도 확인한다.

- [ ] **Step 3: 실제 앱 대상으로 소규모 드라이런 (엔드포인트 준비 여부에 따라 다름)**

이 하네스가 가정하는 `/issue-<전략>` 엔드포인트들은 각 락 전략 브랜치가 별도로 추가하는 것이라, **지금(이 브랜치 기준)은 아직 하나도 존재하지 않을 수 있다.** 이미 `/issue-pessimistic`(또는 과거 이름인 `/issue`)이 존재하는 브랜치가 있다면 그걸 체크아웃해서 드라이런하고, 아직 하나도 없다면 이 스텝은 건너뛰고 Step 2의 스모크 테스트로 충분한 것으로 간주한다(엔드포인트가 생기는 대로 나중에 재검증).

별도 터미널에서 엔드포인트가 있는 브랜치의 앱을 기동한다(이 스텝은 별 창에서 실행 후 유지):
```bash
cd coffee-coupon-api && ./gradlew bootRun
```

앱이 뜨면 캠페인을 시딩하고 campaign_id를 확인한다:
```bash
cd coffee-coupon-api
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon < load-test/seed.sql
```

출력된 `campaign_id` 값(예: `3`)과, 그 브랜치에 실제로 존재하는 전략 이름을 넣어 소규모로 실행한다:
```bash
k6 run -e CAMPAIGN_ID=3 -e STRATEGY=pessimistic -e RATE=10 -e DURATION=2s -e PRE_ALLOCATED_VUS=10 -e MAX_VUS=20 coffee-coupon-api/load-test/coupon-issue-scale.js
```

Expected: `checks_succeeded: 100%`, `http_reqs`가 약 20건, 상태코드가 전부 200(재고 2,000장에 20건뿐이라 전부 성공해야 정상)이다.

- [ ] **Step 4: 커밋**

```bash
git add coffee-coupon-api/load-test/coupon-issue-scale.js
git commit -m "feat: add k6 load script for coupon issue scale comparison"
```

---

### Task 3: `verify.sh` — 결과 검증 스크립트

**Files:**
- Create: `coffee-coupon-api/load-test/verify.sh`

**Interfaces:**
- Consumes: Task 1이 만든 `campaign_id`(첫 번째 위치 인자), `GET /api/coupons/{campaignId}`가 반환하는 `CouponResponse`(`totalQuantity`, `issuedQuantity`, `remainingQuantity` 필드)
- Produces: 과발급이면 exit code 1과 `FAIL` 메시지, 아니면 exit code 0과 `PASS` 메시지(+ 언더셀이 있으면 `NOTE`). 사람이 러너북에서 읽는 최종 판정 출력이라 이후 태스크가 이 출력을 코드로 소비하진 않는다.

- [ ] **Step 1: `verify.sh` 작성**

```bash
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
```

- [ ] **Step 2: 실행 권한 부여**

Run: `chmod +x coffee-coupon-api/load-test/verify.sh`

- [ ] **Step 3: PASS 경로 검증 (Task 2에서 띄워둔 앱 재사용)**

Task 2 Step 3에서 시딩한 `campaign_id`(예: `3`)로 실행한다:
```bash
coffee-coupon-api/load-test/verify.sh 3
```

Expected: `PASS: 과발급 없음 (issued=20, remaining=1980)`과 `NOTE: 언더셀 발생 - 1980장이...` 출력, exit code 0.

Run `echo $?`로 exit code가 `0`인지 재확인한다.

- [ ] **Step 4: FAIL 경로 검증 (과발급 상태를 인위로 만들어서)**

같은 캠페인의 `issued_quantity`를 DB에서 직접 3,000으로 강제로 올려 과발급 상태를 흉내낸다:
```bash
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon -e \
  "UPDATE coupon_campaign SET issued_quantity = 3000 WHERE id = 3;"
coffee-coupon-api/load-test/verify.sh 3; echo "exit=$?"
```

Expected: `FAIL: 과발급 발생 - issuedQuantity(3000) > totalQuantity(2000)`, `exit=1`.

- [ ] **Step 5: 테스트로 어지른 데이터 정리**

```bash
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon -e \
  "DELETE FROM coupon_issue WHERE coupon_campaign_id = 3; DELETE FROM coupon_campaign WHERE id = 3;"
```

Task 2에서 띄운 `./gradlew bootRun`은 Task 4에서 다시 쓰므로 계속 켜둔다(끄고 싶으면 Ctrl+C 후 Task 4에서 다시 기동).

- [ ] **Step 6: 커밋**

```bash
git add coffee-coupon-api/load-test/verify.sh
git commit -m "feat: add result verification script for lock-strategy load test"
```

---

### Task 4: `README.md` — 실행 러너북

**Files:**
- Create: `coffee-coupon-api/load-test/README.md`

**Interfaces:**
- Consumes: Task 1~3에서 만든 `seed.sql`, `coupon-issue-scale.js`, `verify.sh`의 정확한 사용법(파일명, 인자, 환경변수)
- Produces: 사람이 브랜치를 오가며 따라 할 수 있는 문서. 이후 낙관적/분산 락 브랜치에서 이 README를 그대로 재사용한다.

- [ ] **Step 1: `README.md` 작성**

```markdown
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

## 문제 해결

- `verify.sh`가 `FAIL: totalQuantity(...) != expected(2000)`을 내면 `seed.sql`이 제대로 안 돌았거나 다른 campaign_id를 잘못 넣은 것이다.
- k6가 `dropped_iterations`를 많이 보고하면 `MAX_VUS`가 부족해서 목표 QPS를 못 낸 것이다 — 이 자체도 그 락 전략이 해당 QPS를 못 버틴다는 신호이니 위 표의 "인프라 오류" 칸에 기록한다.
- k6 요약에 404가 대량으로 찍히면 `STRATEGY` 값에 오타가 있거나(`no-lock`/`pessimistic`/`optimistic`/`distributed` 중 하나여야 함), 그 전략의 엔드포인트를 추가한 브랜치가 아직 지금 체크아웃한 브랜치에 안 들어와 있는 것이다.
```

- [ ] **Step 2: README에 적힌 순서를 처음부터 끝까지 그대로 따라가며 전체 파이프라인 재검증**

Task 2~3에서 띄워둔 앱을 재사용해서(또는 새로 기동), README의 1~5단계를 그대로 실행한다. 이 브랜치에 아직 `/issue-<전략>` 엔드포인트가 하나도 없다면(Global Constraints 참고), 이 스텝은 Task 2 Step 2의 스모크 테스트로 갈음하고 나중에 엔드포인트가 생긴 뒤 재검증한다.

```bash
cd coffee-coupon-api
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon < load-test/seed.sql
# 출력된 campaign_id를 아래 두 명령에 채워 넣는다 (STRATEGY는 실제 브랜치에 존재하는 전략으로)
k6 run -e CAMPAIGN_ID=<id> -e STRATEGY=pessimistic -e RATE=50 -e DURATION=3s -e PRE_ALLOCATED_VUS=30 -e MAX_VUS=60 load-test/coupon-issue-scale.js
load-test/verify.sh <id>
```

Expected: k6 요약에 `checks_succeeded: 100%`, `verify.sh`가 `PASS`를 출력한다(재고 2,000장에 150건 정도만 시도하므로 전부 200이어야 정상).

- [ ] **Step 3: 테스트로 만든 캠페인 정리, 앱 종료**

```bash
MYSQL_PWD='<local.yaml의 password>' mysql -h 127.0.0.1 -u root -D coffee_coupon -e \
  "DELETE FROM coupon_issue WHERE coupon_campaign_id = <id>; DELETE FROM coupon_campaign WHERE id = <id>;"
```

`./gradlew bootRun`을 실행 중인 터미널에서 Ctrl+C로 종료한다.

- [ ] **Step 4: 커밋**

```bash
git add coffee-coupon-api/load-test/README.md
git commit -m "docs: add runbook for lock strategy load test harness"
```

---

### Task 5: PR 생성

**Files:** 없음 (GitHub 작업)

- [ ] **Step 1: 전체 파일 목록 최종 확인**

Run: `git log --stat develop..HEAD`
Expected: Task 1~4의 커밋 4개, `coffee-coupon-api/load-test/{seed.sql,coupon-issue-scale.js,verify.sh,README.md}` 4개 파일만 포함.

- [ ] **Step 2: 원격에 푸시**

```bash
git push -u origin feature/lock-strategy-load-test
```

- [ ] **Step 3: PR 생성**

```bash
gh pr create --title "feat: 락 전략 비교용 k6 부하테스트 하네스" --body "$(cat <<'EOF'
## Summary
- 락 없음(대조군)/비관적/낙관적/분산 락 네 가지를 peak 6,000 QPS(재고 2,000장, 10초 몰림) 가정으로 동일하게 검증할 수 있는 k6 하네스 추가
- 스펙: docs/superpowers/specs/2026-08-17-lock-strategy-load-test-harness-design.md

## Test plan
- [x] seed.sql 두 번 실행 → 서로 다른 campaign_id 생성 확인
- [x] k6 스크립트 소규모 드라이런으로 200 응답 확인
- [x] verify.sh PASS/FAIL 경로 모두 확인
- [x] README에 적힌 순서 그대로 처음부터 끝까지 재현
EOF
)"
```
