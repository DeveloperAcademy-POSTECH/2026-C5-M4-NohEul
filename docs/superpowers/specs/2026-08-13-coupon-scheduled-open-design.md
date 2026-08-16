# 오픈 시각 기반 쿠폰 발급 설계

- 날짜: 2026-08-13 (2026-08-14 관계형 스키마 분리로 개정)
- 브랜치: `feature/coupon-scheduled-open` (`feature/coupon-issue-pessimistic-lock` 위에서 시작)
- 목적: 쿠폰 도메인에 "특정 시각에 오픈"이라는 현실적인 제약을 추가하고, 지금까지 단조로웠던 2-테이블 스키마(`coupon`, `coupon_issue`)를 "쿠폰 종류"와 "이번 발급 캠페인"으로 분리한 3-테이블 관계형 구조로 재설계한다. 발급 순간의 기술적 판단(락 걸기 전 가드 절 순서, 시간 의존 코드의 테스트 가능성)과, 여러 테이블에 걸친 관계형 스키마 설계 경험을 함께 연습한다. 지금 진행 중인 락 비교 시리즈(비관적→낙관적→분산)는 이 확장된 도메인을 기준으로 다시 진행한다.

## 기획 요청 (원문 톤)

> 카페 앱에서 매일 오전 10시에 "아메리카노 10% 할인 쿠폰"을 선착순 100명에게 발급하고 싶어요. 알림 받은 사람들이 정각에 몰릴 텐데 재고 이상으로 나가면 안 되고, 한 사람당 하나만 받을 수 있어야 해요. 10시 되기 전엔 발급 안 되게 막아주세요.

## 범위

- 발급 시점에만 집중한다. 할인 적용/쿠폰 사용/만료는 범위 밖(다음 단계 후보).
- 인증 없음, 1인 1매 제한은 기존 그대로 유지한다.
- "정각에 실제로 몰리는 트래픽"을 스레드 타이밍으로 재현하는 것은 범위 밖으로 둔다(가상 시계 제어가 필요한 별개의 어려운 문제).
- 연관관계는 **진짜 FK 제약이나 JPA `@ManyToOne` 없이, 값만 저장하는 `Long` 컬럼**으로 표현한다(B안). `@ManyToOne`/DB FK 제약으로의 승격은 범위 밖 — 필요해지면 별도 스펙으로 다룬다.
- `CouponController`/`CouponService`라는 이름은 그대로 유지한다. API 겉면(`/api/coupons/...`)과 서비스 이름은 "쿠폰 발급"이라는 사용자 관점 개념을 나타내고, 그 아래 엔티티 계층만 `CouponTemplate`/`CouponCampaign`으로 쪼갠다.
- 할인율 외의 할인 형태(정액 할인 등)는 다루지 않는다 — 데이터 모델링 문제이지 이번 스펙의 관심사(관계형 설계 + 동시성)와 무관하다고 판단해 범위에서 제외.

## 도메인 모델 — 3-테이블 분리

지금까지 `Coupon` 테이블 하나가 "쿠폰이 뭔지(할인 정보)"와 "이번엔 언제·몇 개 발급하는지(캠페인 정보)"를 함께 담고 있었다. 이걸 나눈다.

| 엔티티 | 테이블 | 컬럼 | 역할 |
|---|---|---|---|
| `CouponTemplate` (신규) | `coupon_template` | `id`, `name`, `discountRate: Int` | 쿠폰의 "종류". 한 번 만들면 여러 캠페인에서 재사용 가능 |
| `CouponCampaign` (기존 `Coupon`을 개명) | `coupon_campaign` | `id`, `couponTemplateId: Long`, `totalQuantity: Int`, `issuedQuantity: Int`, `openAt: LocalDateTime` | 특정 템플릿을 "이번엔 몇 개, 언제부터" 발급할지 |
| `CouponIssue` (기존 유지, FK 컬럼명만 변경) | `coupon_issue` | `id`, `couponCampaignId: Long`(기존 `couponId`에서 개명), `userId`, `issuedAt` | 실제 발급 이력. `(couponCampaignId, userId)` UNIQUE 제약은 그대로 유지 |

`coupon_template → coupon_campaign → coupon_issue`로 이어지는 1:N 관계 체인이 생긴다. 같은 "아메리카노 10% 할인" 템플릿으로 "8/14 캠페인", "8/15 캠페인"을 따로 열 수 있다.

**리포지토리 계층**: `CouponRepository`는 `CouponCampaignRepository`로 개명하고(기존 `findByIdForUpdate`/`PESSIMISTIC_WRITE` 로직은 그대로 옮김), `CouponTemplateRepository`를 새로 추가한다.

## 오픈 전 체크 순서 — 락보다 먼저

```kotlin
@Transactional
fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
    val openAt = couponCampaignRepository.findOpenAtById(couponCampaignId)        // ① 락 없는 스칼라 조회
        ?: throw CouponNotFoundException(couponCampaignId)

    if (LocalDateTime.now(clock).isBefore(openAt)) {
        throw CouponNotYetOpenException(couponCampaignId, openAt)                 // ② 오픈 전이면 락 걸기 전에 컷
    }

    val campaign = couponCampaignRepository.findByIdForUpdate(couponCampaignId)   // ③ 오픈 후에만 락 조회
        .orElseThrow { CouponNotFoundException(couponCampaignId) }

    if (couponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId, userId)) {
        throw DuplicateIssueException(couponCampaignId, userId)
    }

    if (campaign.issuedQuantity >= campaign.totalQuantity) {
        throw CouponSoldOutException(couponCampaignId)
    }

    campaign.issuedQuantity += 1
    couponCampaignRepository.save(campaign)

    return couponIssueRepository.save(CouponIssue(couponCampaignId = couponCampaignId, userId = userId))
}
```

락/오픈 시각 판단 로직 자체는 어제 설계와 동일하다 — 대상이 `Coupon`에서 `CouponCampaign`으로 바뀌었을 뿐이다. DB 조회가 2번(①③) 나가는 이유(오픈 전 요청은 락 비용을 안 치르게 하려는 의도적 트레이드오프)도 그대로 유지된다.

**주의: ①은 엔티티가 아닌 `openAt` 단일 컬럼만 읽는 스칼라 프로젝션(`findOpenAtById`)이어야 한다.** 처음에는 `findById`로 `CouponCampaign` 엔티티를 미리 읽는 안을 채택했으나, 같은 트랜잭션 안에서 엔티티를 한 번 영속성 컨텍스트에 올린 뒤 ③에서 `findByIdForUpdate`로 다시 조회하면 SQL은 `SELECT ... FOR UPDATE`로 다시 나가지만 Hibernate가 1차 캐시(영속성 컨텍스트의 identity map)에 이미 올라온 같은 인스턴스를 그대로 반환해버려 비관적 락이 조용히 무력화되는 문제가 있었다. 30개 동시 요청 테스트(`CouponServiceConcurrencyTest`)에서 기대한 "1명만 성공"이 아니라 "10명 성공"으로 재현되어 발견했다. `findOpenAtById`는 엔티티가 아닌 `LocalDateTime`만 반환하므로 영속성 컨텍스트에 아무것도 등록되지 않아 이 충돌이 발생하지 않는다.

## 테스트 가능한 시간 — `Clock` 주입

어제 설계와 동일. `LocalDateTime.now()`를 직접 호출하지 않고 `java.time.Clock`을 생성자로 주입받아, 테스트에서 `Clock.fixed(...)`로 "오픈 3초 전" 같은 상황을 결정론적으로 만든다.

## 에러 처리

| 상황 | 예외 | HTTP | 비고 |
|---|---|---|---|
| 캠페인을 찾을 수 없음 | `CouponNotFoundException` | 404 | 기존 유지 (파라미터가 `couponId`→`couponCampaignId`로 개명) |
| 오픈 전 요청 | `CouponNotYetOpenException` | 403 | 어제 설계와 동일 |
| 재고 소진 | `CouponSoldOutException` | 409 | 기존 유지 |
| 중복 발급 | `DuplicateIssueException` | 409 | 기존 유지 |

## API 영향 (구현 계획에서 상세화)

`CouponController`의 경로(`/api/coupons/{id}/issue`, `/api/coupons/{id}`)는 바꾸지 않는다. 다만 `GET /api/coupons/{id}` 응답(`CouponResponse`)이 지금은 `Coupon` 하나에서 바로 채워지는데, 이제 `CouponCampaign` + 그 캠페인이 참조하는 `CouponTemplate`을 조합해야 `name`/`discountRate` 같은 템플릿 정보를 응답에 담을 수 있다. 정확한 응답 DTO 필드 구성과 조회 방식(서비스에서 2번 조회 vs 별도 조합 메서드)은 스펙 범위가 아니라 구현 계획 단계에서 정한다.

## 테스트

- **단위 테스트** (`CouponServiceTest` 확장): `Clock.fixed`로 "오픈 3초 전" 고정 → `CouponNotYetOpenException` 발생 검증. 목 대상이 `CouponCampaignRepository`로 바뀐다.
- **동시성 테스트** (`CouponServiceConcurrencyTest`): `openAt`을 이미 지난 시각으로 세팅해서 기존 "30개 동시 요청 → 1명만 성공" 검증은 그대로 유지. `CouponCampaign` 저장 전에 `CouponTemplate`을 먼저 저장해야 한다.
- **신규**: `CouponTemplate` → `CouponCampaign` → `CouponIssue` 체인이 올바르게 저장/조회되는지 확인하는 리포지토리 테스트 (기존 `CouponRepositoryTest` 패턴 확장).

## 브랜치/이슈 전략

- `feature/coupon-issue-pessimistic-lock`(PR #4, 검증된 락 구현 포함)을 베이스로 만든 `feature/coupon-scheduled-open` 브랜치에서 계속 진행한다 — 락 구현은 그대로 재사용, 대상 엔티티만 바뀐다.
- 이 기능은 "락"이 아니라 "도메인 요구사항"이라 이슈 #3와 성격이 다르다. 별도 이슈를 만들어 PR이 `Closes #3, Closes #<신규>` 두 개를 닫는 형태로 간다.
- 이후 낙관적 락/분산 락도 이 확장된 도메인(템플릿/캠페인 분리 + 오픈 시각) 기준으로 다시 비교한다.
