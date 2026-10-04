# 락 전략 비교 부하테스트 하네스

락 없음(대조군)/비관적/낙관적/synchronized(JVM 락)/분산 락이 가정한 트래픽(peak 6,000 QPS, 재고 2,000장, 10초 몰림)에서
과발급 없이 동작하는지(또는 얼마나 과발급되는지) 동일한 기준으로 검증하는 도구 세트입니다.
설계 배경은 `docs/superpowers/specs/2026-08-17-lock-strategy-load-test-harness-design.md` 참고.

### 왜 6,000 QPS인가

알림 대상 30만 명 · 재고 2,000장 · 실제 시도율 20%(6만 명) · 오픈 직후 몰림 10초를 가정해서 역산한 값입니다. 근거는 위 스펙 문서 참고.

**로컬 실행 기본값은 500입니다.** 6,000은 이 시스템이 감당해야 한다고 가정한 설계 목표치이고, `coupon-issue-scale.js`의 `RATE` 기본값은 500입니다. 로컬 단일 인스턴스(HikariCP 풀 10개, k6·앱·DB가 한 장비를 공유)에서 6,000 QPS를 걸면 락 전략 차이보다 커넥션 풀/장비 포화가 결과를 지배해 비교가 어려워집니다. 500만으로도 no-lock 과발급 재현과 전략 간 상대 비교라는 목적은 충분합니다(아래 "왜 issuedQuantity가 아니라 실제 행 수로 판정하는가" 참고. 이미 500 req/s 실측으로 확인됨). 6,000으로 절대 처리량을 보고 싶으면 `-e RATE=6000`을 명시적으로 붙이세요.

## 사전 준비

- k6 (`brew install k6`)
- jq (`brew install jq`)
- mysql CLI
- MySQL이 떠 있고 앱이 거기에 연결될 것 (루트 README의 "실행 방법" 참고)
- 각 전략이 `/api/coupons/{campaignId}/issue-<전략>` 형태의 별도 엔드포인트(`issue-no-lock`/`issue-pessimistic`/`issue-optimistic`/`issue-synchronized`/`issue-distributed`)로 앱에 존재해야 한다. 각 전략은 자기 브랜치에서 구현되지만 브랜치가 순차적으로 develop에 머지되므로(구현→머지 반복), 가장 최근에 머지된 브랜치에는 그때까지의 엔드포인트가 전부 함께 있다.

## 한 번에 돌리기

앱을 띄워 둔 상태에서 저장소 루트에서 실행한다. 전략마다 엔드포인트 확인 → 시딩 → k6 → `verify.sh`를 차례로 하고, 마지막에 아래 결과 표와 같은 칸의 마크다운 표를 출력한다.

```bash
coffee-coupon-api/load-test/run-all.sh                        # no-lock → pessimistic → optimistic → synchronized → distributed
coffee-coupon-api/load-test/run-all.sh no-lock synchronized   # 고른 전략만
RATE=10 DURATION=2s coffee-coupon-api/load-test/run-all.sh    # 작게 먼저 확인
```

- **미구현 전략은 건너뛴다**: 없는 캠페인(0번)에 먼저 요청해서 `COUPON_NOT_FOUND`가 오면 엔드포인트가 있는 것으로, 스프링 기본 404가 오면 없는 것으로 보고 건너뛴다. 표에는 "건너뜀"으로 남는다.
- **상태 코드 집계**: `coupon-issue-scale.js`가 응답을 성공(200) / 락 거부(403, 409) / 인프라 오류(그 외) 세 카운터로 세고, `run-all.sh`가 `--summary-export` JSON에서 읽어 표에 넣는다. 실제 발급 수와 판정은 `verify.sh` 결과에서 읽는다.
- DB 비밀번호는 `MYSQL_PWD`가 있으면 그 값을, 없으면 `application-local.yaml`의 값을, 그것도 없으면 docker-compose 기본값(`coffee`)을 쓴다.
- MySQL을 3306이 아닌 포트로 띄웠다면(예: `MYSQL_PORT=3308 docker compose up`) `export MYSQL_TCP_PORT=3308`을 먼저 지정한다. `mysql` 명령은 이 환경 변수로 포트를 정하고, 지정하지 않으면 3306에 붙는다. 앱도 같은 MySQL을 보고 있어야 한다.
- 결과는 `load-test/results/<날짜-시간>/`에 전략별 k6 출력, k6 요약 JSON, 검증 결과, `summary.md`(결과 표)로 남는다(gitignore 대상).
- 한 전략이 과발급(FAIL)이어도 멈추지 않고 나머지 전략을 계속 측정한다.

## 전략 하나를 검증하는 순서 (수동)

브랜치를 오갈 필요 없이, **엔드포인트가 다 모여 있는 브랜치 하나에서 앱을 한 번만 띄워두고** 전략(엔드포인트)만 바꿔가며 반복하면 된다. 아래 명령은 저장소 루트에서 실행한다.

1. 앱을 기동한다 (별도 터미널, 계속 띄워둠).
   ```bash
   cd coffee-coupon-api
   ./gradlew bootRun
   ```
2. 검증할 전략을 고르고, DB 비밀번호를 넣어 둔다. **`no-lock`(대조군)부터 먼저 하는 걸 권장**한다. 락이 없을 때 실제로 얼마나 과발급되는지 먼저 확인해두면 나머지 전략의 결과를 해석할 기준이 생긴다.
   ```bash
   STRATEGY=no-lock      # 이후 pessimistic → optimistic → synchronized 순으로 반복
   export MYSQL_PWD=coffee   # docker-compose MySQL 기본값
   ```
   직접 설치한 MySQL을 `application-local.yaml`로 연결해 쓰고 있다면 그 파일의 비밀번호를 쓴다. yaml에 따옴표로 감싸져 있으면 따옴표는 빼야 한다.
   ```bash
   export MYSQL_PWD=$(grep 'password:' coffee-coupon-api/src/main/resources/application-local.yaml | awk '{print $2}' | tr -d '"')
   ```
3. 캠페인을 새로 시딩하고 `campaign_id`를 확보한다. **전략마다 매번 새로 시딩한다.** 이전 전략이 다 써버린 캠페인을 재사용하면 안 된다.
   ```bash
   mysql -h 127.0.0.1 -u root -D coffee_coupon < coffee-coupon-api/load-test/seed.sql
   ```
4. 부하를 발사한다 (기본값 500 req/s × 10초 = 총 5,000건. 로컬 실행 기본값이고, 설계 목표치는 6,000).
   ```bash
   k6 run -e CAMPAIGN_ID=<3단계에서 나온 값> -e STRATEGY=$STRATEGY coffee-coupon-api/load-test/coupon-issue-scale.js
   ```
   작게 먼저 확인하고 싶으면 `-e RATE=10 -e DURATION=2s -e PRE_ALLOCATED_VUS=10 -e MAX_VUS=20`으로 축소해서 실행. 설계 목표치(6,000)로 돌리려면 `-e RATE=6000`.
5. 결과를 검증한다. `verify.sh`는 `campaign.issuedQuantity` 카운터를 신뢰하지 않고 **`coupon_issue` 테이블의 실제 행 수**로 과발급 여부를 판정한다(이유는 아래 "왜 issuedQuantity가 아니라 실제 행 수로 판정하는가" 참고). 2단계에서 넣은 `MYSQL_PWD`를 그대로 쓴다.
   ```bash
   coffee-coupon-api/load-test/verify.sh <campaign_id>
   ```
   기본 접속 정보는 `DB_HOST=127.0.0.1`/`DB_USER=root`/`DB_NAME=coffee_coupon`이며, 다르면 같은 이름의 환경변수로 오버라이드한다.
6. 3~5번을 `STRATEGY`만 바꿔서 반복한다 (no-lock → pessimistic → optimistic → synchronized). 아래 표 형식으로 결과를 기록한다. 성공/실패 카운트는 k6 요약의 상태코드 분포에서 확인한다. **5xx(커넥션 타임아웃 등 인프라 오류)는 403/409(락 로직에 의한 정상 거부)와 반드시 분리해서 별도 칸에 적는다.** 섞으면 "락이 막은 것"과 "인프라가 못 버틴 것"을 구분할 수 없다.

| 락 전략 | 성공(200) | 락에 의한 실패(403/409) | 인프라 오류(5xx/dropped) | 최종 issuedQuantity | 과발급 | 비고(처리량/p95 등) |
|---|---|---|---|---|---|---|
| **락 없음 (대조군)** | 2,001 | 3,001 | 0 | 2,001 | **있음 (1장)** | throughput≈500 req/s, avg 3ms, p95 10ms |
| 비관적 락 | 2,000 | 3,000 | 0 | 2,000 | 없음 | throughput≈500 req/s, avg 66ms, p95 244ms |
| 낙관적 락 | 2,000 | 3,001 | 0 | 2,000 | 없음 | throughput≈500 req/s, avg 153ms, p95 355ms. 언더셀 없음(재시도로 전량 소진) |
| synchronized (JVM 락) | 2,000 | 3,000 | 0 | 2,000 | 없음 | throughput≈488 req/s, avg 1.01s, p95 1.89s. 대기 VU 최대 912개 |
| 분산 락 | - | - | - | - | 미실측 | 미구현 (스펙/계획만 존재: `docs/superpowers/plans/2026-08-26-coupon-issue-distributed-lock.md`) |

측정 조건: `feature/coupon-issue-synchronized`(commit `6f5be12`), 로컬 단일 인스턴스(앱은 IDE에서 직접 실행, MySQL 8.4는 docker-compose), `RATE=500`(기본값), `DURATION=10s`(기본값), `run-all.sh`로 네 전략을 연달아 측정, 2026-10-04. no-lock은 레이스 컨디션이 확률적이라 실행마다 과발급 수가 크게 달라진다.

이전 측정(2026-08-27, `develop` commit `883e343`, 로컬 설치 MySQL): 락 없음 8장 과발급(avg 791ms), 비관적 락 avg 1.44s, 낙관적 락 avg 90ms. MySQL 실행 환경과 코드가 달라 위 표와 수치를 직접 비교하지 않는다.

## 결과 해석 시 주의

- **왜 issuedQuantity가 아니라 실제 행 수로 판정하는가**: 락이 없으면 두 요청이 동시에 같은 재고(예: 1999)를 읽고, 둘 다 "1999 < 2000" 체크를 통과해 각자 발급한다. 이때 카운터(`campaign.issuedQuantity`)는 Lost Update로 실제보다 적게 올라갈 수 있지만, `coupon_issue` 행은 두 요청 모두 insert하므로 실제 발급 건수는 카운터보다 많아질 수 있다. `no-lock` 전략에서 실측한 예: 500 req/s × 10초 부하 후 `campaign.issuedQuantity`는 2,000(정상처럼 보임)이었지만 `coupon_issue` 실제 행 수는 2,214건이었다(214장 과발급, 카운터가 이를 숨기고 있었음). 그래서 `verify.sh`는 반드시 `coupon_issue` 테이블을 직접 세어서 판정한다.
- **DB 커넥션 풀**: 앱의 Hikari 커넥션 풀 크기(기본값 10)가 모든 전략에 동일하게 적용되는 조건이다. 실측 처리량이 6,000 req/s에 못 미쳐도 그게 "락 자체의 한계"인지 "커넥션 풀 크기의 한계"인지는 이 표만으로는 구분 안 된다. 절대 수치보다 **전략 간 상대 비교**에 집중한다.
- **낙관적 락의 언더셀**: 재시도 로직 없이 구현했다면 `issuedQuantity`가 2,000에 못 미치는 채로 끝날 수 있다. 이건 과발급이 아니라 낙관적 락의 특성이므로 "과발급" 칸에는 "없음"으로 적고, 비고에 언더셀 수치를 남긴다.
- **synchronized는 단일 서버 전용**: 락이 JVM 메모리에 있어서 서버를 여러 대 띄우면 서로를 막지 못한다. 이 하네스는 로컬 단일 인스턴스라 다른 전략과 같은 조건에서 비교할 수 있지만, 결과를 다중 서버 환경에 그대로 옮기면 안 된다.
- **처리량/지연시간은 판정에 안 쓴다**: k6 요약의 처리량(req/s)·p95 등은 비고 칸에 참고용으로 기록만 한다. 이번 하네스의 PASS/FAIL은 정합성(과발급 여부)만으로 결정한다.

## 문제 해결

- `verify.sh`가 `FAIL: totalQuantity(...) != expected(2000)`을 내면 `seed.sql`이 제대로 안 돌았거나 다른 campaign_id를 잘못 넣은 것이다.
- k6가 `dropped_iterations`를 많이 보고하면 `MAX_VUS`가 부족해서 목표 QPS를 못 낸 것이다. 이 자체도 그 락 전략이 해당 QPS를 못 버틴다는 신호이니 위 표의 "인프라 오류" 칸에 기록한다.
- k6 요약에 404가 대량으로 찍히면 `STRATEGY` 값에 오타가 있거나(`no-lock`/`pessimistic`/`optimistic`/`synchronized`/`distributed` 중 하나여야 함), 그 전략의 엔드포인트를 추가한 브랜치가 아직 지금 체크아웃한 브랜치에 안 들어와 있는 것이다.
- `mysql`이 `Access denied for user 'root'`를 내면 `MYSQL_PWD`가 틀린 것이다. `application-local.yaml`에서 비밀번호를 읽었다면 따옴표까지 들어가지 않았는지 확인한다(`echo ${#MYSQL_PWD}`로 글자 수 확인).
- 명령을 붙여넣었는데 `cmdsubst>`가 뜨고 멈추면 줄바꿈 때문에 `$( ... )`가 둘로 쪼개진 것이다. `Ctrl + C`로 취소하고 한 줄로 다시 붙여넣는다.
- 앱을 재시작했더니 시딩한 캠페인이 사라졌다면 `ddl-auto: create` 설정 때문이다. 앱을 먼저 띄우고 그다음에 시딩한다.
- `run-all.sh`가 "앱이 방금 시딩한 campaign을 찾지 못했습니다"로 멈추면, `mysql` 명령(시딩)과 앱이 서로 다른 DB를 보고 있는 것이다. 예: 앱은 docker MySQL을 보는데 `MYSQL_TCP_PORT`를 안 넣어 시딩이 로컬 MySQL(3306)로 간 경우. 이 상태로 측정하면 모든 요청이 404(인프라 오류)로 집계된다.
