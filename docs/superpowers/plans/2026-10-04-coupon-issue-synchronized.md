# 쿠폰 발급 synchronized(JVM 락) Implementation Plan

> 진행 방식: 단계마다 Claude가 코드를 제안하고, 사용자에게 설명한 뒤 허락을 받고 나서 수정한다. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 캠페인별 `synchronized` 락을 트랜잭션 바깥에서 거는 `POST /api/coupons/{campaignId}/issue-synchronized` 엔드포인트를 추가하고, 락 위치(트랜잭션 안/밖)와 락 범위(JVM 하나)가 결과를 어떻게 바꾸는지 스레드 실험과 k6로 확인한다.

**Architecture:** 바깥 빈 `SynchronizedCouponIssuer`(트랜잭션 없음)가 `ConcurrentHashMap<Long, Any>` + `computeIfAbsent`로 캠페인별 락 객체를 얻고, `synchronized(lock)` 안에서 안쪽 빈 `SynchronizedCouponIssueProcessor`(`@Transactional`)를 호출한다. 안쪽 빈의 프록시가 커밋까지 끝낸 뒤 반환하므로, 락은 커밋 이후에 풀린다. 발급 흐름은 공유 헬퍼 없이 Processor가 직접 가지며, 재고 증가는 `@Version`을 우회하는 `incrementIssuedQuantityRaw`를 쓴다.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, MySQL(로컬), JUnit 5, `@SpringBootTest`(컨트롤러/동시성 테스트), k6

**Spec:** `docs/superpowers/specs/2026-10-03-coupon-issue-synchronized-design.md`

## Global Constraints

- 새 의존성 추가 금지.
- 기존 세 전략(`issueNoLock`, `issuePessimistic`, `issueOptimistic`)과 `CouponIssueAttempter`는 수정하지 않는다. 기존 전략 결과에 영향이 없어야 한다.
- Processor는 공유 헬퍼를 쓰지 않는다. `issueNoLock`과의 중복은 의도된 것이다(스펙 3절).
- 틀린 버전(트랜잭션 안 `synchronized`)은 엔드포인트로 만들지 않고 테스트 전용 빈으로만 둔다.
- 커밋은 단계마다 나누고, 커밋 컨벤션(`<type>: <설명>`)을 따른다.

---

## Task 1: 실패하는 동시성 테스트

**Files:**
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`

- [x] `SynchronizedCouponIssuer`를 주입받고, 재고 1장 캠페인에 30명이 동시에 `issue`를 호출하면 성공 1명, 실패 29명, `issuedQuantity` = 1인지 확인하는 테스트를 추가한다. 기존 `seedOpenCampaign`, `runConcurrently`를 그대로 쓴다.
- [x] 실행해서 컴파일 에러(클래스 없음)로 실패하는지 확인한다.
- [x] 커밋: `test: synchronized 동시성 테스트 추가`

## Task 2: `SynchronizedCouponIssueProcessor`

**Files:**
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/SynchronizedCouponIssueProcessor.kt`

- [x] `@Service` + `@Transactional fun issue(couponCampaignId, userId): CouponIssue`
- [x] 흐름: `findById` → 오픈 시각(`Clock`) → `existsByCouponCampaignIdAndUserId` → 재고 확인 → `incrementIssuedQuantityRaw` → `CouponIssue` 저장. 예외는 기존 예외 클래스를 그대로 쓴다.
- [x] 커밋: `feat: synchronized 발급 흐름(Processor) 추가`

## Task 3: `SynchronizedCouponIssuer` + 저장소 주석

**Files:**
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/SynchronizedCouponIssuer.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponCampaignRepository.kt`

- [x] 트랜잭션 없는 `@Service`. `private val locks = ConcurrentHashMap<Long, Any>()`, `computeIfAbsent(couponCampaignId) { Any() }`로 락을 얻고 `synchronized(lock) { processor.issue(...) }`.
- [x] `incrementIssuedQuantityRaw` 주석의 "issueNoLock 전용"을 "락 없음·synchronized 전용"으로 고친다.
- [x] Task 1 테스트가 통과하는지 확인한다. 기존 동시성 테스트도 함께 통과해야 한다.
- [x] 커밋: `feat: 캠페인별 synchronized 락(Issuer) 추가`

## Task 4: 엔드포인트

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`

- [x] 다른 전략과 진입점을 맞추려고 `CouponService.issueSynchronized()`를 추가한다. `SynchronizedCouponIssuer`에 넘기기만 하고, `@Transactional`은 붙이지 않는다(붙이면 안쪽 Processor가 합류해서 커밋이 락 해제 뒤로 밀린다).
- [x] `POST /api/coupons/{couponId}/issue-synchronized`. 요청/응답, 에러 코드는 다른 전략과 같다. `@Operation` 설명에 "단일 서버에서만 유효"를 적는다.
- [x] `CouponService` 생성자 파라미터가 늘어서, `CouponServiceTest`의 생성자 호출에 `SynchronizedCouponIssuer` 목을 넘긴다.
- [x] 정상 발급 컨트롤러 테스트를 추가하고 통과를 확인한다.
- [x] 커밋: `feat: issue-synchronized 엔드포인트 추가`

## Task 5: 스레드 실험

**Files:**
- Create: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/ScratchJvmLockExperimentTest.kt`

- [x] assertion 없이 println만 남긴다(`ScratchRefreshSnapshotProbeTest`와 같은 방식).
- [x] E0 락 없음, E1 `@Transactional` 안 `synchronized`(테스트 전용 `open` 빈), E2 트랜잭션 바깥 `synchronized`, E3 `ReentrantLock`, E4 락 객체 두 개.
- [x] 결과: 재고 1장, 30명, 여러 라운드의 성공 수.
- [x] 원인: E1·E2는 스레드 3~5개로 "락 획득(읽은 재고) / 락 해제 / 커밋 완료" 순서를 찍는다. 커밋 시점은 `TransactionSynchronization.afterCommit`.
- [x] 커밋: `test: JVM 락 위치 실험 추가`

## Task 6: k6

**Files:**
- Modify: `coffee-coupon-api/load-test/coupon-issue-scale.js`

- [x] `VALID_STRATEGIES`에 `synchronized`를 추가한다.
- [x] 같은 날, 같은 장비에서 네 전략을 연달아 측정하고 결과를 기록한다.
- [x] 커밋: `chore: k6에 synchronized 전략 추가` (실제로는 #19 자동화와 함께 `8c88999 feat: 락 전략 부하테스트 자동화(run-all.sh)와 synchronized 전략 추가`로 커밋. 측정 결과는 `aace75d`)

## Task 7: 문서

**Files:**
- Modify: `coffee-coupon-api/load-test/README.md`, `README.md`, `docs/superpowers/specs/2026-08-17-lock-strategy-load-test-harness-design.md`

- [x] 전략 목록, 엔드포인트 표, 결과 표에 synchronized를 추가한다.
- [x] 하네스 스펙 결정 목록에 "(2026-10-03 추가 결정) 비교 대상에 synchronized(JVM 락)를 추가한다." 한 줄을 넣는다.
- [x] 커밋: `docs: synchronized 전략 문서 반영` (실제로는 `6f5be12`, `aace75d`와 이 커밋으로 나눠 반영)

## Task 8: PR

- [ ] `develop`으로 PR을 연다. 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 구조를 따르고, 올리기 전에 사용자에게 문구를 확인받는다.
