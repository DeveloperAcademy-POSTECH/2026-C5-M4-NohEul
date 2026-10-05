# 쿠폰 발급 synchronized(JVM 락) 설계

- 날짜: 2026-10-03
- 브랜치: `feature/coupon-issue-synchronized`
- 목적: DB 락(비관적·낙관적)과 달리 **애플리케이션(JVM) 안에서** 거는 락인 `synchronized`로 같은 쿠폰 발급 문제를 풀고, 나머지 세 전략(락 없음/비관적/낙관적)과 같은 조건에서 비교한다. 특히 "락을 어디에 거느냐(트랜잭션 안/밖)"와 "락이 어디까지 유효하냐(JVM 하나)"가 결과를 어떻게 바꾸는지 확인한다.

## 범위

- `POST /api/coupons/{campaignId}/issue-synchronized` 엔드포인트를 새로 만들고, k6 부하테스트 비교 대상에 추가한다.
- JVM 락의 동작과 함정을 보여주는 스레드 실험(E0~E3)을 테스트 코드로 작성한다. 틀린 버전(트랜잭션 안 synchronized)은 엔드포인트로 만들지 않는다.
- 범위 밖
  - `ReentrantLock` 전략 (엔드포인트, 스레드 실험 모두)
  - 락 맵 정리(끝난 캠페인의 락 제거), 여러 서버 대응 — "알려진 한계"로만 기록한다.
  - 기존 세 전략의 공유 헬퍼 분리 — 별도 이슈로 다룬다.

## 1. 락을 어디에 거나 — 트랜잭션 바깥

`@Transactional`은 프록시가 메서드를 감싸는 방식이라, 커밋은 **메서드가 반환된 뒤** 프록시에서 일어난다. 그래서 `@Transactional` 메서드 안에서 `synchronized`를 걸면 순서가 이렇게 된다.

```
프록시: 트랜잭션 시작
  └─ 실제 메서드: 락 획득 → 읽기 → 확인 → +1 → INSERT → 락 해제
프록시: 커밋   ← 락은 이미 풀렸다
```

락 해제와 커밋 사이에 다음 요청이 들어와 **커밋 전의 재고**를 읽으면 과발급된다. 따라서 락이 커밋까지 감싸도록, 락은 트랜잭션 **바깥**에서 잡는다.

```
바깥 (트랜잭션 없음): 락 획득
  └─ 안쪽 빈의 @Transactional 메서드 호출 → 프록시가 커밋까지 끝내고 반환
바깥: 락 해제
```

안쪽 메서드는 **다른 빈**에 둔다. 같은 클래스 안에서 부르면 프록시를 거치지 않아 `@Transactional`이 적용되지 않는다(self-invocation, 낙관적 락의 `CouponIssueAttempter` 분리와 같은 이유).

| 클래스 | 역할 | 트랜잭션 |
|---|---|---|
| `SynchronizedCouponIssuer` | 캠페인별 락을 잡고 안쪽 빈을 호출 | 없음 |
| `SynchronizedCouponIssueProcessor` | 발급 흐름 전체(검증 → 재고 증가 → 저장) | `@Transactional` |

락은 컨트롤러가 아니라 별도 서비스 빈(`SynchronizedCouponIssuer`)에서 잡는다. 컨트롤러는 요청을 넘기는 역할만 하고, 동시성 로직은 서비스 계층에 모은다.

## 2. 락 범위 — 캠페인별 락

락의 범위는 **보호하려는 공유 자원의 범위**와 같아야 한다. 재고는 캠페인마다 따로 있으므로 캠페인 단위로 잠근다. 비관적 락(`SELECT ... FOR UPDATE`)이 캠페인 행 하나만 잠그는 것과 범위가 같아 비교도 공정하다.

```kotlin
private val locks = ConcurrentHashMap<Long, Any>()

fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
    val lock = locks.computeIfAbsent(couponCampaignId) { Any() }
    synchronized(lock) {
        return processor.issue(couponCampaignId, userId)
    }
}
```

- 전역 락 하나(`private val lock = Any()`)로 하면 재고가 서로 다른 캠페인끼리도 한 줄로 서게 된다.
- **`computeIfAbsent`여야 하는 이유**: 같은 캠페인의 첫 요청 두 개가 동시에 들어왔을 때 "없으면 만들어 넣기"가 원자적이지 않으면, 두 스레드가 **서로 다른 락 객체**를 만들어 각자 잡고 동시에 통과한다. `ConcurrentHashMap.computeIfAbsent`는 같은 키에 대해 생성 함수가 한 번만 실행되고 모든 스레드가 같은 객체를 받도록 보장한다. (일반 `HashMap`이나 "get 해보고 없으면 put"으로는 이 보장이 없다.)
- 락 객체는 `Any()`다. 이 전략은 `synchronized`(모든 객체에 있는 모니터 락)를 쓰므로 별도 락 클래스가 필요 없다.

## 3. 발급 흐름 — 전략마다 독립, 중복 허용

`SynchronizedCouponIssueProcessor`는 기존 공유 헬퍼(`CouponIssueAttempter.completeIssue` 등)를 쓰지 않고, 발급 흐름 전체를 직접 가진다.

**이유**: 공유 헬퍼 때문에 한 전략의 변경이 다른 전략의 결과를 바꾼 사례가 있었다(`cd55675` — 낙관적 락을 위해 `@Version`을 추가하자, 같은 헬퍼를 쓰던 락 없음 엔드포인트까지 버전 체크를 받아 과발급이 재현되지 않았다). 이 프로젝트의 목적은 전략 간 비교이므로, 중복이 생기더라도 **각 엔드포인트가 자기 전략만 순수하게 보여주는 것**을 우선한다.

흐름과 검증 순서는 다른 전략과 같다.

```kotlin
@Transactional
fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
    val campaign = couponCampaignRepository.findById(couponCampaignId)
        .orElseThrow { CouponNotFoundException(couponCampaignId) }

    if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
        throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
    }
    if (couponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId, userId)) {
        throw DuplicateIssueException(couponCampaignId, userId)
    }
    if (campaign.issuedQuantity >= campaign.totalQuantity) {
        throw CouponSoldOutException(couponCampaignId)
    }

    couponCampaignRepository.incrementIssuedQuantityRaw(couponCampaignId)
    return couponIssueRepository.save(CouponIssue(couponCampaignId = couponCampaignId, userId = userId))
}
```

- **재고 증가는 `incrementIssuedQuantityRaw`(`@Version`을 우회하는 벌크 UPDATE)를 쓴다.** 일반 `save()`로 올리면 `@Version` 체크(낙관적 락)가 조용히 함께 동작해서, 과발급을 막은 게 synchronized 덕분인지 흐려진다. 이 쿼리를 쓰면 과발급을 막는 건 **순수하게 synchronized뿐**이다.
- 이 흐름은 `issueNoLock`과 거의 같다. 의도된 중복이다 — 두 엔드포인트의 차이가 "바깥에 synchronized가 있느냐" 하나뿐이라는 게 그대로 드러난다.

## 4. API

`POST /api/coupons/{campaignId}/issue-synchronized` — 요청/응답과 에러 코드는 다른 전략과 같다.

| 상황 | HTTP | `code` |
|---|---|---|
| 발급 성공 | 200 | - |
| 오픈 전 | 403 | `COUPON_NOT_YET_OPEN` |
| 캠페인 없음 | 404 | `COUPON_NOT_FOUND` |
| 재고 소진 | 409 | `COUPON_SOLD_OUT` |
| 중복 발급 | 409 | `DUPLICATE_ISSUE` |

Swagger `@Operation` 설명에 "단일 서버에서만 유효"를 명시한다.

## 5. 알려진 한계

- **JVM 안에서만 유효하다.** 서버를 여러 대 띄우면 서버마다 락 맵이 따로 생겨 서로를 막지 못한다. (스레드 실험 E3로 흉내 내 확인)
- **락 맵이 계속 커진다.** 끝난 캠페인의 락 객체를 지우지 않는다. 언제 지워야 안전한지(지우는 순간 다른 스레드가 그 락을 쥐고 있다면?)는 이번 범위에서 다루지 않는다.
- **무한정 기다린다.** `synchronized`는 타임아웃이나 포기가 없어서, 요청이 몰리면 뒤쪽 요청은 락을 얻을 때까지 계속 대기한다(톰캣 스레드를 붙잡는다). `ReentrantLock.tryLock(timeout)`과의 차이는 이번 범위에서 다루지 않는다.
- **같은 사용자의 다른 캠페인 동시 요청은 막지 않는다.** 지금 규칙(캠페인당 1인 1매)은 캠페인 락과 `(couponCampaignId, userId)` UNIQUE 제약으로 충분하다. 캠페인을 넘나드는 사용자 규칙(예: 하루 2장)이 생기면 사용자 단위 락과 락 순서가 필요하다.

## 6. 테스트

- **`CouponServiceConcurrencyTest`**: 재고 1장 캠페인에 30명 동시 요청 → 성공 1명, `issuedQuantity` = 1 (기존 비관적 락 테스트와 같은 형태).
- **`CouponControllerTest`**: `issue-synchronized` 정상 발급 테스트.
- **스레드 실험 `ScratchJvmLockExperimentTest`**: 결과가 확률적이라 assertion 없이 println으로만 남긴다(기존 `ScratchRefreshSnapshotProbeTest`와 같은 방식). 두 가지를 본다.
  - **결과(성공 수)**: 재고 1장, 30명 동시 요청을 여러 라운드 반복하고 라운드별 성공 수를 출력한다.
  - **원인(순서 로그)**: E1·E2는 스레드 3~5개로 따로 돌려, 스레드마다 "락 획득(읽은 재고)", "락 해제", "커밋 완료"를 찍는다. 커밋 시점은 `TransactionSynchronization.afterCommit` 콜백으로 기록한다. "락 해제"가 "커밋 완료"보다 먼저 찍히고, 다음 스레드가 커밋 전 재고를 읽는다면 1절의 주장이 확인된다.
  - E1 재현용 `@Transactional` + `@Synchronized` 메서드는 테스트 전용 빈으로 만든다. Kotlin 클래스는 기본이 `final`이라 `open`으로 열어야 프록시가 만들어진다.

| # | 실험 | 확인하려는 것 |
|---|---|---|
| E0 | 락 없음 | 기준선 (과발급) |
| E1 | `@Transactional` 메서드 안에서 `synchronized` | 락이 커밋보다 먼저 풀리는가(순서 로그), 그래서 과발급되는가(성공 수) |
| E2 | 트랜잭션 바깥에서 `synchronized` | 커밋이 락 해제보다 먼저인가(순서 로그), 그래서 1명만 성공하는가(성공 수) |
| E3 | 락 객체 두 개(스레드 절반씩) | 서버 두 대처럼 락이 나뉘면 다시 과발급되는가 |

- **k6**: 같은 날, 같은 장비에서 네 전략(락 없음/비관적/낙관적/synchronized)을 연달아 측정한다. 8/27 측정값은 "재측정에서도 경향이 같았는가"의 비교 근거로 남긴다.

## 7. 영향받는 문서·파일

| 대상 | 변경 |
|---|---|
| `CouponCampaignRepository.incrementIssuedQuantityRaw` 주석 | 지금은 "issueNoLock 전용"이라고 적혀 있다. synchronized도 쓰게 되므로 "락 없음·synchronized 전용"으로 고친다 |
| `load-test/coupon-issue-scale.js` | `VALID_STRATEGIES`에 `synchronized` 추가 (없으면 k6가 실행을 거부함) |
| `load-test/README.md` | 전략 목록, 실행 순서, 결과표에 synchronized 행 추가 |
| 루트 `README.md` | 소개("세 가지 전략"), 엔드포인트 표, 실험 결과 표 |
| 하네스 스펙 `2026-08-17-lock-strategy-load-test-harness-design.md` | 본문은 그대로 두고 결정 목록에 한 줄 추가: "(2026-10-03 추가 결정) 비교 대상에 synchronized(JVM 락)를 추가한다. 단일 서버 전용이지만 측정 환경이 로컬 단일 인스턴스라 같은 조건에서 비교 가능하다." |

## 결정된 사항 요약

- 범위: 올바른 버전은 엔드포인트로 만들어 k6로 비교하고, 틀린 버전(E1)과 서버 두 대(E3)는 스레드 실험으로만 확인
- 락 위치: 트랜잭션 바깥, 별도 서비스 빈(`SynchronizedCouponIssuer`). 안쪽 `@Transactional` 흐름은 다른 빈(`SynchronizedCouponIssueProcessor`)
- 락 범위: 캠페인별 락(`ConcurrentHashMap<Long, Any>` + `computeIfAbsent`)
- 발급 흐름: 전략마다 독립, 중복 허용 — synchronized에만 적용하고 기존 전략 분리는 별도 이슈
- 재고 증가: `@Version`을 우회하는 `incrementIssuedQuantityRaw`
