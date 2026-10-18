# 커피 쿠폰 발급 베이스라인 설계

- 날짜: 2026-08-11
- 브랜치: `feature/coupon-issue-baseline`
- 목적: 락 없는 베이스라인 쿠폰 발급 기능을 구현해, 이후 동시 요청 테스트로 초과 발급(race condition)을 재현하고 비관적/낙관적/분산 락을 단계적으로 비교하기 위한 기준선을 만든다.

## 범위

- 쿠폰은 **단일 종류(1종)**만 다룬다. 여러 캠페인 관리 기능은 범위 밖.
- 사용자 인증은 없다. 요청 body에 `userId`를 직접 실어 보낸다.
- 한 사용자는 동일 쿠폰을 **1인 1매**만 받을 수 있다.
- 동시성 제어(락)는 이 스펙의 범위가 아니다. 의도적으로 걸지 않는다.

## 1. 아키텍처

Controller → Service → Repository(Spring Data JPA) → MySQL 의 단순한 3계층 구조.
락은 의도적으로 걸지 않는다 — 다음 단계에서 동시 요청 테스트로 초과 발급을 재현한 뒤, 그 위에 락 전략을 하나씩 붙이는 것이 이 프로젝트의 목적이기 때문이다.

## 2. 컴포넌트

### 엔티티

- `Coupon`: `id`(PK), `name`, `totalQuantity`(총 수량), `issuedQuantity`(발급된 수량, 기본값 0)
- `CouponIssue`: `id`(PK), `couponId`(FK), `userId`, `issuedAt`(`LocalDateTime`)
  - `(couponId, userId)` 조합에 DB unique 제약을 걸어 1인 1매를 DB 레벨에서도 보장한다.
  - 역할: (1) 발급 요청 시 중복 여부 확인 (2) 발급 이력 조회 근거.
  - 재고 차감(카운터 증가)은 `Coupon.issuedQuantity`가, 중복 방지는 `CouponIssue`가 각각 담당한다.

### API

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/coupons/{couponId}/issue` | body `{ "userId": Long }` — 쿠폰 발급 요청 |
| `GET` | `/api/coupons/{couponId}` | 쿠폰 정보 + 잔여 수량 조회 |

### 클래스

`CouponController`, `CouponService`, `CouponRepository`, `CouponIssueRepository` (Spring Data JPA)

## 3. 데이터 흐름 (발급 요청)

```
1. Client → POST /api/coupons/{couponId}/issue { userId }
2. Controller → CouponService.issue(couponId, userId)
3. Service: coupon = couponRepository.findById(couponId)
             없으면 → 404 Not Found
4. Service: couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)
             이미 있으면 → 409 Duplicate
5. Service: coupon.issuedQuantity < coupon.totalQuantity 확인
             아니면 → 409 Sold Out
6. Service: coupon.issuedQuantity += 1 → couponRepository.save(coupon)
7. Service: couponIssueRepository.save(CouponIssue(couponId, userId, now()))
8. Controller → 200 OK, 발급된 쿠폰 정보 반환
```

3~6번 사이 "확인 후 증가(check-then-act)" 구간이 이 프로젝트가 재현하려는 race condition 지점이다. 베이스라인에서는 이 구간을 그대로 두고, 다음 단계에서 동시 요청 테스트로 실제 초과 발급을 증명한다.

## 4. 에러 처리

`@RestControllerAdvice`로 커스텀 예외를 HTTP 응답에 매핑한다.

| 상황 | 예외 | HTTP 상태 | 응답 예시 |
|---|---|---|---|
| 존재하지 않는 쿠폰 | `CouponNotFoundException` | 404 | `{ "code": "COUPON_NOT_FOUND", "message": "..." }` |
| 이미 발급받은 사용자 | `DuplicateIssueException` | 409 | `{ "code": "DUPLICATE_ISSUE", "message": "..." }` |
| 재고 소진 | `CouponSoldOutException` | 409 | `{ "code": "COUPON_SOLD_OUT", "message": "..." }` |

### 알려진 한계 (의도적)

- 4번(중복 확인)과 6~7번(저장) 사이, 5번(재고 확인)과 6번(증가) 사이 모두 동시 요청 시 race condition이 발생할 수 있다.
- `CouponIssue`의 DB unique 제약이 중복 발급의 최후 방어선 역할은 한다. 이 경우 `DataIntegrityViolationException`이 발생하며, 이것도 `DuplicateIssueException`으로 매핑해 처리한다.
- 재고 소진 쪽은 이런 방어선이 없어 실제로 초과 발급이 재현된다 — 이것이 다음 단계의 테스트 대상이다.

## 5. 테스트

베이스라인 단계의 테스트는 **단일 스레드 기준 정상 동작 검증**에 집중한다. 동시 요청으로 race condition을 재현하는 테스트는 다음 단계(동시성 문제 재현 테스트 작성)에서 별도로 작성하며, 이 스펙의 범위가 아니다.

### `CouponServiceTest` (단위 테스트, repository는 목 처리)

- 정상 발급: 재고 있고 미발급 사용자 → 발급 성공, `issuedQuantity` 1 증가
- 재고 소진: `issuedQuantity == totalQuantity` → `CouponSoldOutException`
- 중복 발급: 이미 `CouponIssue` 존재 → `DuplicateIssueException`
- 존재하지 않는 쿠폰: → `CouponNotFoundException`

### `CouponControllerTest` (`@SpringBootTest` + 실제 DB 또는 테스트 컨테이너)

- 위 4가지 케이스를 API 레벨(HTTP 상태 코드/응답 바디)로 검증
- `GET /api/coupons/{couponId}` 잔여 수량 조회 정상 동작 확인

## 결정된 사항 요약 (브레인스토밍 과정에서 확정)

- 쿠폰 종류: 단일 1종 (Approach: 단일 쿠폰)
- 사용자 식별: 인증 없이 요청에 `userId` 직접 포함
- 중복 발급: 1인 1매로 제한
- API 범위: 발급 + 잔여수량 조회
- 재고 차감 방식: **카운터 필드 방식(A안)** 채택 — `Coupon.issuedQuantity` 증가. 이후 비관적/낙관적 락을 이 필드(또는 버전 컬럼)에 적용할 예정이라 락 실습과 자연스럽게 이어짐. (기각안: `CouponIssue` 행 수를 `COUNT(*)`로 세는 방식 — 락 대상이 불명확해 기각)
