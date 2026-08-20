# 쿠폰 발급 낙관적 락(@Version) 설계

- 날짜: 2026-08-20
- 브랜치: `feature/coupon-issue-optimistic-lock`
- 목적: 비관적 락(`issue-pessimistic`, #3)이 Lost Update를 막는 대신 락 대기라는 비용을 치르는 것과 대조적으로, 낙관적 락(`@Version`)으로 같은 문제를 "충돌 감지 후 재시도" 방식으로 풀고 두 전략을 비교한다.

## 범위

- `CouponCampaign`에 `@Version` 필드를 추가하고, `CouponService.issueOptimistic` + `POST /api/coupons/{campaignId}/issue-optimistic` 엔드포인트를 새로 만든다.
- 커밋 시점 충돌(`ObjectOptimisticLockingFailureException`) 발생 시 재시도-백오프 처리, 재시도 소진 시 409 응답.
- 분산 락은 범위 밖(별도 브랜치/스펙).
- 부하테스트 하네스(`load-test/`)는 이미 `STRATEGY=optimistic`을 지원하도록 만들어져 있어(`coupon-issue-scale.js`), 엔드포인트만 추가되면 별도 작업 없이 바로 비교 가능 — 이번 스펙 범위엔 안 넣는다.

## 1. 스키마 변경

`CouponCampaign`에 버전 컬럼을 추가한다.

```kotlin
@Entity
class CouponCampaign(
    var couponTemplateId: Long,
    var totalQuantity: Int,
    var issuedQuantity: Int = 0,
    var openAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long = 0
}
```

Hibernate는 이 엔티티를 `UPDATE`할 때마다 자동으로 `WHERE id = ? AND version = ?`를 붙이고, 영향받은 행이 0건이면 `ObjectOptimisticLockingFailureException`을 던진다. 이게 낙관적 락의 전체 메커니즘이다 — 애플리케이션 코드가 직접 버전을 비교하지 않는다.

## 2. 서비스 로직 — `issueOptimistic`

비관적 락은 `findOpenAtById`(스칼라 프로젝션) → `findByIdForUpdate`(락 포함 엔티티 조회)로 같은 행을 두 번 조회해야 했다(Hibernate 1차 캐시가 두 번째 조회를 무력화시키는 버그를 피하려고). 낙관적 락은 **같은 트랜잭션 안에서 캠페인 엔티티를 한 번만 조회**하므로 그 문제 자체가 발생하지 않는다.

```kotlin
@Transactional
fun issueOptimistic(couponCampaignId: Long, userId: Long): CouponIssue {
    var lastException: ObjectOptimisticLockingFailureException? = null
    for (attempt in 1..optimisticMaxAttempts) {
        try {
            val campaign = couponCampaignRepository.findById(couponCampaignId)
                .orElseThrow { CouponNotFoundException(couponCampaignId) }
            if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
                throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
            }
            val result = completeIssue(campaign, couponCampaignId, userId)
            couponCampaignRepository.flush()  // 아래 "왜 flush()가 필요한가" 참고 — 없으면 재시도가 동작하지 않는다
            return result
        } catch (e: ObjectOptimisticLockingFailureException) {
            lastException = e
            if (attempt < optimisticMaxAttempts) Thread.sleep(OPTIMISTIC_RETRY_DELAY_MS)
        }
    }
    throw CouponIssueConflictException(couponCampaignId, lastException)
}
```

재고 체크/증가/저장은 기존 `completeIssue(campaign, couponCampaignId, userId)`를 그대로 재사용한다(`ensureNotAlreadyIssued` → `ensureStockAvailable` → `incrementIssuedQuantity` → `saveIssue`) — 이미 비관적 락/락없음 두 메서드가 공유하고 있는 헬퍼라 낙관적 락도 세 번째로 재사용하면 된다.

**재시도할 때마다 `findById`로 캠페인을 다시 조회하는 게 핵심이다.** 충돌은 "내가 읽은 버전이 이미 낡았다"는 뜻이므로, 낡은 엔티티 그대로 재시도하면 또 충돌만 난다. 매 attempt마다 최신 버전을 다시 읽어야 재시도가 의미를 가진다.

### 왜 `couponCampaignRepository.flush()`가 필요한가

이건 스펙 셀프 리뷰 중에 발견한, 구현했으면 조용히 재시도가 무력화됐을 함정이다. `@Transactional`은 프록시가 메서드 실행을 감싸는 방식이라, 실제 커밋(과 그 직전의 flush)은 **메서드 바디가 끝나고 프록시로 제어가 돌아온 뒤** 일어난다. `incrementIssuedQuantity`의 `save()`는 기본적으로 변경 사항을 큐에 쌓아둘 뿐, 즉시 `UPDATE`를 보내지 않는다 — 즉 `flush()`를 명시적으로 안 부르면, 버전 충돌을 감지하는 실제 `UPDATE ... WHERE version = ?`가 `issueOptimistic` 메서드가 `return`한 **이후**(프록시가 트랜잭션을 커밋하는 시점)에야 실행된다. 그 시점엔 이미 이 메서드의 `try/catch`를 벗어난 뒤라서, `ObjectOptimisticLockingFailureException`이 재시도 루프에 전혀 안 잡히고 그대로 호출자에게 새 나간다 — 재시도 로직 자체가 유명무실해진다.

`JpaRepository.flush()`([Spring Data JPA `JpaRepository` 인터페이스](https://github.com/spring-projects/spring-data-jpa/blob/main/src/main/java/org/springframework/data/jpa/repository/support/SimpleJpaRepository.java)에 정의)를 `completeIssue` 호출 직후, `return`하기 **전에** 명시적으로 불러서 `UPDATE`를 그 자리에서 즉시 실행시켜야, 버전 충돌이 `try` 블록 안에서(아직 메서드를 빠져나가기 전에) 터지고 `catch`가 잡을 수 있다. 기존 `incrementIssuedQuantity`(비관적 락/락없음과 공유하는 헬퍼)는 건드리지 않는다 — `flush()`는 `issueOptimistic`에서만 필요하다.

## 3. 재시도 파라미터

- `CouponService` 생성자에 `optimisticMaxAttempts: Int = 3` 추가 — `Clock`과 같은 방식으로, 테스트에서 `1`로 오버라이드해 "재시도 없음"을 재현할 수 있다.
- 백오프는 고정 지연(`OPTIMISTIC_RETRY_DELAY_MS = 20`) — 지수 백오프 등 복잡한 전략은 이 학습 단계의 목적(재시도 유무 비교)에 비해 과함.
- 재시도 로직은 Spring Retry(`@Retryable`) 같은 프레임워크를 새로 끌어오지 않고 서비스 메서드 안에 직접 루프로 구현한다 — 새 의존성 없이, 재시도 과정 자체가 코드에 그대로 보여서 학습 목적에 맞음.

## 4. 재시도 소진 시 처리

새 예외 `CouponIssueConflictException`을 추가하고, `GlobalExceptionHandler`에 409로 매핑한다. 기존 409(`COUPON_SOLD_OUT`/`DUPLICATE_ISSUE`)와는 다른 코드(`COUPON_ISSUE_CONFLICT`)로 구분한다 — 원인이 다르기 때문이다(재고 소진/중복 발급은 비즈니스 규칙 위반, 이건 동시 수정 충돌).

```kotlin
class CouponIssueConflictException(
    couponId: Long,
    cause: Throwable? = null,
) : RuntimeException("재시도 소진: campaign=$couponId", cause)
```

```kotlin
@ExceptionHandler(CouponIssueConflictException::class)
fun handleIssueConflict(ex: CouponIssueConflictException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse("COUPON_ISSUE_CONFLICT", ex.message ?: "Concurrent modification conflict"))
```

## 5. 컨트롤러

`POST /api/coupons/{campaignId}/issue-optimistic` — 기존 `issue-pessimistic`/`issue-no-lock`과 동일한 패턴(Swagger `@Operation`/`@ApiResponses`에 409(`COUPON_ISSUE_CONFLICT`) 케이스 추가).

## 6. 에러 처리 / 알려진 한계

- **언더셀**: 재시도 없이(`optimisticMaxAttempts=1`) 동시 요청이 몰리면, 재고가 남았는데도 최종 `issuedQuantity`가 `totalQuantity`에 못 미칠 수 있다. 이건 과발급이 아니라 낙관적 락의 특성이다 — `load-test/README.md`에 이미 이 케이스에 대한 판정 기준("과발급 없음 + 언더셀 있음"은 PASS로 기록)이 마련돼 있다.
- **재시도 중 `Thread.sleep`**: 서비스 메서드가 `@Transactional`이므로, 재시도 대기 동안에도 DB 커넥션을 쥔 채로 스레드가 블로킹된다. 비관적 락의 락 대기와 다른 종류의 "묶임"이지만, 커넥션 풀 관점에서는 비슷한 리스크가 있다 — 이번 스펙에서 해결하지는 않고 알려진 한계로만 기록한다.
- **`ensureNotAlreadyIssued` 중복 체크와 재시도의 상호작용**: 같은 유저가 재시도 사이에 실제로 중복 발급을 시도한 경우는 `DuplicateIssueException`(기존 로직)이 그대로 우선 처리된다 — 재시도 루프는 `ObjectOptimisticLockingFailureException`만 잡고 다른 예외는 그대로 던지므로 섞이지 않는다.

## 7. 테스트

- `CouponServiceTest`: 정상 발급/품절/중복/미오픈 케이스를 낙관적 락 버전으로 추가. 버전 충돌이 1회 발생한 뒤 재시도로 성공하는 케이스 하나 추가(리포지토리를 스텁/스파이해서 첫 `save()`에서 `ObjectOptimisticLockingFailureException`을 던지도록 구성).
- `CouponServiceConcurrencyTest`: 기존 `runConcurrently`(`ExecutorService` + `CountDownLatch`) 패턴을 재사용해 두 케이스를 검증한다.
  - `optimisticMaxAttempts=3`(기본): 재고 1개 캠페인에 N명 동시 요청 → 성공 1명, 나머지는 재시도로 대부분 정리되거나 `CouponIssueConflictException`.
  - `optimisticMaxAttempts=1`(재시도 없음): 같은 시나리오에서 실패(충돌) 비율이 훨씬 높아짐을 확인 — Issue #5가 요구한 "재시도 유무에 따른 성공/실패 횟수 비교".

## 결정된 사항 요약

- 재시도 로직: 서비스 메서드 안에 직접 루프(Spring Retry 미사용, 새 의존성 없음)
- 재시도 파라미터: 고정 횟수 3회 + 고정 지연 20ms
- 재시도 소진 시: 새 예외 `CouponIssueConflictException` → 409 `COUPON_ISSUE_CONFLICT`
- 재시도 유무 테스트: 별도 엔드포인트를 만들지 않고, `optimisticMaxAttempts`를 생성자 파라미터로 노출해 테스트에서 1로 오버라이드
