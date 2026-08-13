# 오픈 시각 기반 쿠폰 발급 설계

- 날짜: 2026-08-13
- 브랜치: `feature/coupon-scheduled-open` (`feature/coupon-issue-pessimistic-lock` 위에서 시작)
- 목적: 단일 쿠폰 도메인에 "특정 시각에 오픈"이라는 현실적인 제약을 추가해, 발급 순간의 기술적 판단 지점(락 걸기 전 가드 절 순서, 시간 의존 코드의 테스트 가능성)을 더 깊게 연습한다. 지금 진행 중인 락 비교 시리즈(비관적→낙관적→분산)는 이 확장된 도메인을 기준으로 다시 진행한다.

## 기획 요청 (원문 톤)

> 카페 앱에서 매일 오전 10시에 "아메리카노 10% 할인 쿠폰"을 선착순 100명에게 발급하고 싶어요. 알림 받은 사람들이 정각에 몰릴 텐데 재고 이상으로 나가면 안 되고, 한 사람당 하나만 받을 수 있어야 해요. 10시 되기 전엔 발급 안 되게 막아주세요.

## 범위

- 여전히 단일 쿠폰(1종)만 다룬다. 이번에 추가되는 새 축은 "언제 발급이 열리는가" 하나뿐이다.
- 발급 시점에만 집중한다. 할인 적용/쿠폰 사용/만료는 범위 밖(다음 단계 후보).
- 인증 없음, 1인 1매 제한은 기존 그대로 유지한다.
- "정각에 실제로 몰리는 트래픽"을 스레드 타이밍으로 재현하는 것은 범위 밖으로 둔다(가상 시계 제어가 필요한 별개의 어려운 문제).

## 도메인 모델 변경

`Coupon`에 `openAt: LocalDateTime` 필드를 추가한다.

## 오픈 전 체크 순서 — 락보다 먼저

```kotlin
@Transactional
fun issue(couponId: Long, userId: Long): CouponIssue {
    val couponPreview = couponRepository.findById(couponId)          // ① 락 없는 조회
        .orElseThrow { CouponNotFoundException(couponId) }

    if (LocalDateTime.now(clock).isBefore(couponPreview.openAt)) {
        throw CouponNotYetOpenException(couponId, couponPreview.openAt)  // ② 오픈 전이면 락 걸기 전에 컷
    }

    val coupon = couponRepository.findByIdForUpdate(couponId)         // ③ 오픈 후에만 락 조회
        .orElseThrow { CouponNotFoundException(couponId) }

    if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
        throw DuplicateIssueException(couponId, userId)
    }

    if (coupon.issuedQuantity >= coupon.totalQuantity) {
        throw CouponSoldOutException(couponId)
    }

    coupon.issuedQuantity += 1
    couponRepository.save(coupon)

    return couponIssueRepository.save(CouponIssue(couponId = couponId, userId = userId))
}
```

DB 조회가 2번(①③) 나가는 건, 오픈 전 요청이 락 비용(커넥션 점유 + `FOR UPDATE`)을 아예 안 치르게 하려는 의도적 트레이드오프다. 오픈런 상황에서 "아직 안 열렸는데 미리 두드리는 요청"이 실제로 많을 걸 감안하면 합리적인 선택이다.

## 테스트 가능한 시간 — `Clock` 주입

`LocalDateTime.now()`를 서비스 안에서 직접 호출하면 "오픈 3초 전" 같은 상황을 테스트하려고 실제로 대기하거나 시스템 시간을 조작해야 해서 테스트가 느리고 불안정해진다. 대신 `java.time.Clock`을 생성자로 주입받는다.

```kotlin
@Service
class CouponService(
    private val couponRepository: CouponRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock = Clock.systemDefaultZone(),  // 운영에선 기본값, 테스트에선 교체
) { ... }
```

테스트에서 `Clock.fixed(...)`로 "지금은 9시 59분"/"지금은 10시 정각"을 즉시 만들 수 있다.

## 에러 처리 (추가되는 것)

| 상황 | 예외 | HTTP | 비고 |
|---|---|---|---|
| 오픈 전 요청 | `CouponNotYetOpenException` | 403 | `425 Too Early`가 의미상 더 정확하지만 실무 호환성 때문에 403을 우선 채택. 필요하면 425로 교체 가능 |

## 테스트

- **단위 테스트** (`CouponServiceTest` 확장): `Clock.fixed`로 "오픈 3초 전" 고정 → `CouponNotYetOpenException` 발생 검증
- **동시성 테스트** (`CouponServiceConcurrencyTest`): `openAt`을 이미 지난 시각으로 세팅해서 기존 "30개 동시 요청 → 1명만 성공" 검증은 그대로 유지

## 브랜치/이슈 전략

- `feature/coupon-issue-pessimistic-lock`(PR #4, 검증된 락 구현 포함)을 베이스로 `feature/coupon-scheduled-open` 브랜치를 새로 만들어 그 위에 이 도메인 기능을 얹는다 — 락 구현을 다시 짜지 않고 재사용한다.
- 이 기능은 "락"이 아니라 "도메인 요구사항"이라 이슈 #3와 성격이 다르다. 별도 이슈를 만들어 PR이 `Closes #3, Closes #<신규>` 두 개를 닫는 형태로 간다.
- 이후 낙관적 락/분산 락도 이 확장된 도메인(오픈 시각 포함) 기준으로 다시 비교한다.
