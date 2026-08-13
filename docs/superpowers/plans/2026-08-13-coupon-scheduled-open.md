# 오픈 시각 기반 쿠폰 발급 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Coupon`에 오픈 시각(`openAt`)을 추가해, 오픈 전 발급 요청을 비관적 락을 걸기 전에 거부하고, 이 판단을 결정론적으로 테스트할 수 있도록 `Clock`을 주입한다.

**Architecture:** `CouponService.issue()`에서 락 없는 `findById`로 먼저 `openAt`만 확인해 오픈 전이면 즉시 거부하고, 오픈 후에만 기존 `findByIdForUpdate`(`PESSIMISTIC_WRITE`)로 넘어간다. 시간 판단은 `LocalDateTime.now(clock)`을 통해서만 하고, `Clock`은 생성자로 주입해 프로덕션에선 `Clock.systemDefaultZone()`, 테스트에선 `Clock.fixed(...)`를 쓴다.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, Mockito(단위 테스트), `@SpringBootTest`(통합/동시성 테스트)

## Global Constraints

- 스펙 문서: `docs/superpowers/specs/2026-08-13-coupon-scheduled-open-design.md`
- 브랜치: `feature/coupon-issue-pessimistic-lock`(`dd8e2f1`, `findByIdForUpdate`+`PESSIMISTIC_WRITE` 락 포함) 위에서 시작한 `feature/coupon-scheduled-open`
- 오픈 전 요청 응답: HTTP 403, 에러 코드 `COUPON_NOT_YET_OPEN` (기존 `GlobalExceptionHandler` 패턴과 동일하게 `ErrorResponse(code, message)` 반환)
- 기존 `CouponServiceConcurrencyTest`(재고 1개, 동시 요청 30개 → 성공 1명)는 계속 통과해야 한다
- 이 계획의 범위는 발급 시점의 오픈 시각 판단까지다. 할인 적용/쿠폰 사용/만료는 범위 밖(스펙 문서 참조)

---

### Task 1: `Coupon.openAt` + `CouponNotYetOpenException` + `CouponService` 가드 절 + `Clock` 빈

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/config/ClockConfig.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`

**Interfaces:**
- Consumes: 기존 `CouponRepository.findByIdForUpdate(id: Long): Optional<Coupon>` (`CouponRepository.kt`), `JpaRepository`가 제공하는 `findById(id: Long): Optional<Coupon>` (신규 사용, 선언 불필요)
- Produces:
  - `Coupon(name: String, totalQuantity: Int, issuedQuantity: Int = 0, openAt: LocalDateTime = LocalDateTime.now())` — 기존 3-인자 호출부(다른 테스트 파일들)는 그대로 컴파일됨
  - `class CouponNotYetOpenException(couponId: Long, openAt: LocalDateTime) : RuntimeException`
  - `CouponService(couponRepository, couponIssueRepository, clock: Clock = Clock.systemDefaultZone())` — Task 3(`GlobalExceptionHandler`)이 이 예외를 소비함

**참고 — Spring 빈 주입과 Kotlin 기본값의 차이:** `clock: Clock = Clock.systemDefaultZone()`는 순수 Kotlin 코드에서 인자를 생략하고 호출할 때만 적용되는 기본값이다. Spring이 리플렉션으로 생성자를 호출해 빈을 만들 때는 기본값을 인식하지 못하고 `Clock` 타입 빈을 컨텍스트에서 찾으려 하는데, 기본 Spring Boot는 `Clock` 빈을 자동 등록하지 않는다. 그래서 `ClockConfig`로 `Clock` 빈을 직접 등록해야 `@SpringBootTest`(예: 다음 태스크에서 만질 동시성 테스트)와 실제 앱 구동이 깨지지 않는다. `CouponServiceTest`(Mockito, `CouponService(...)`를 직접 `new`)는 순수 Kotlin 호출이라 이 빈 없이도 기본값이 그대로 적용된다.

- [ ] **Step 1: `Coupon`에 `openAt` 필드 추가**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt` 전체를 다음으로 교체:

```kotlin
package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import java.time.LocalDateTime

@Entity
class Coupon(
    var name: String,
    var totalQuantity: Int,
    var issuedQuantity: Int = 0,
    var openAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
```

- [ ] **Step 2: `CouponNotYetOpenException` 추가**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt` 전체를 다음으로 교체:

```kotlin
package com.coffee_coupon_api.exception

import java.time.LocalDateTime

class CouponNotFoundException(couponId: Long) : RuntimeException("Coupon not found: $couponId")

class CouponSoldOutException(couponId: Long) : RuntimeException("Coupon sold out: $couponId")

class DuplicateIssueException(couponId: Long, userId: Long) :
    RuntimeException("Coupon $couponId already issued to user $userId")

class CouponNotYetOpenException(couponId: Long, openAt: LocalDateTime) :
    RuntimeException("Coupon $couponId not yet open. Opens at $openAt")
```

- [ ] **Step 3: `CouponServiceTest`에 실패하는 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`의 import 블록에 아래 3줄 추가 (기존 import 아래):

```kotlin
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
```

`` `존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다` `` 테스트 바로 아래에 새 테스트 추가:

```kotlin
    @Test
    fun `오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`() {
        val openAt = LocalDateTime.of(2026, 8, 13, 10, 0)
        val threeSecondsBeforeOpen = openAt.minusSeconds(3).atZone(ZoneId.systemDefault()).toInstant()
        val fixedClock = Clock.fixed(threeSecondsBeforeOpen, ZoneId.systemDefault())
        val serviceWithFixedClock = CouponService(couponRepository, couponIssueRepository, fixedClock)
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 0, openAt = openAt)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))

        assertThrows(CouponNotYetOpenException::class.java) {
            serviceWithFixedClock.issue(1L, 100L)
        }
    }
```

- [ ] **Step 4: 새 테스트만 실패하는지 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest" --rerun`
Expected: 컴파일은 되지만(`CouponNotYetOpenException` 클래스는 Step 2에서 이미 만들었음) `오픈 시각 이전에 발급 요청하면...` 테스트가 FAIL — 아직 `CouponService`가 이 시나리오에서 아무 예외도 던지지 않고 `couponRepository.findByIdForUpdate(1L)`을 호출하려다가 스텁이 없어 `CouponNotFoundException`을 던지므로, `assertThrows(CouponNotYetOpenException::class.java)`가 다른 예외 타입을 잡고 실패한다.

- [ ] **Step 5: `CouponService`에 `Clock` 주입 + 오픈 전 가드 절 구현**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt` 전체를 다음으로 교체:

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponRepository
import java.time.Clock
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CouponService(
    private val couponRepository: CouponRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    @Transactional
    fun issue(couponId: Long, userId: Long): CouponIssue {
        val couponPreview = couponRepository.findById(couponId)
            .orElseThrow { CouponNotFoundException(couponId) }

        if (LocalDateTime.now(clock).isBefore(couponPreview.openAt)) {
            throw CouponNotYetOpenException(couponId, couponPreview.openAt)
        }

        val coupon = couponRepository.findByIdForUpdate(couponId)
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

    @Transactional(readOnly = true)
    fun getCoupon(couponId: Long): Coupon =
        couponRepository.findById(couponId)
            .orElseThrow { CouponNotFoundException(couponId) }
}
```

- [ ] **Step 6: `Clock` 빈 등록**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/config/ClockConfig.kt` 새로 생성:

```kotlin
package com.coffee_coupon_api.config

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
```

- [ ] **Step 7: 새 테스트는 통과, 기존 테스트는 왜 깨지는지 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest" --rerun`
Expected: `오픈 시각 이전에 발급 요청하면...`은 PASS. 하지만 `발급 가능한 쿠폰은 정상적으로 발급된다`, `이미 발급받은 사용자는 DuplicateIssueException이 발생한다`, `재고가 소진되면 CouponSoldOutException이 발생한다` 3개는 FAIL — 이 세 테스트는 `couponRepository.findByIdForUpdate(1L)`만 스텁해뒀는데, `issue()`가 이제 그 앞에서 `couponRepository.findById(1L)`을 먼저 호출하기 때문이다. 스텁이 없는 Mockito 목은 `Optional`을 리턴하는 메서드에 대해 기본적으로 `Optional.empty()`를 반환하므로 `CouponNotFoundException`이 먼저 터진다. 이건 실수가 아니라 예상된 실패다 — 다음 스텝에서 고친다.

- [ ] **Step 8: 기존 3개 테스트에 `findById` 스텁 추가**

`발급 가능한 쿠폰은 정상적으로 발급된다` 테스트에서:
```kotlin
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 0)
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)
```
를
```kotlin
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 0)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)
```
로 변경.

`이미 발급받은 사용자는 DuplicateIssueException이 발생한다` 테스트에서:
```kotlin
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 1)
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(true)
```
를
```kotlin
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 1)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(true)
```
로 변경.

`재고가 소진되면 CouponSoldOutException이 발생한다` 테스트에서:
```kotlin
        val coupon = Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 1)
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)
```
를
```kotlin
        val coupon = Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 1)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)
```
로 변경.

(`존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`, `getCoupon` 관련 2개 테스트는 변경 불필요 — 각각 이유는 위 설명 참고.)

- [ ] **Step 9: `CouponServiceTest` 전체 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest" --rerun`
Expected: 8개 테스트(기존 7개 + 신규 1개) 전부 PASS

- [ ] **Step 10: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt \
        coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt \
        coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt \
        coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/config/ClockConfig.kt \
        coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt
git commit -m "feat: reject coupon issue requests before openAt"
```

---

### Task 2: `GlobalExceptionHandler`에 403 매핑 추가

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`

**Interfaces:**
- Consumes: `CouponNotYetOpenException(couponId: Long, openAt: LocalDateTime)` (Task 1에서 생성)
- Produces: `GlobalExceptionHandler.handleNotYetOpen(ex: CouponNotYetOpenException): ResponseEntity<ErrorResponse>` — 이후 태스크에서 직접 소비하는 곳 없음(HTTP 계층 최종 매핑)

- [ ] **Step 1: 실패하는 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`의 import 블록에 추가:

```kotlin
import java.time.LocalDateTime
```

`` `중복 저장으로 인한 무결성 위반이면 409를 반환한다` `` 테스트 아래에 새 테스트 추가:

```kotlin
    @Test
    fun `오픈 전 발급 요청이면 403을 반환한다`() {
        val response = handler.handleNotYetOpen(CouponNotYetOpenException(1L, LocalDateTime.of(2026, 8, 13, 10, 0)))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("COUPON_NOT_YET_OPEN", response.body?.code)
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest" --rerun`
Expected: FAIL — `handleNotYetOpen`이 아직 `GlobalExceptionHandler`에 없어서 컴파일 에러(`unresolved reference`)

- [ ] **Step 3: 핸들러 구현**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt` 전체를 다음으로 교체:

```kotlin
package com.coffee_coupon_api.exception

import com.coffee_coupon_api.dto.ErrorResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(CouponNotFoundException::class)
    fun handleNotFound(ex: CouponNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("COUPON_NOT_FOUND", ex.message ?: "Coupon not found"))

    @ExceptionHandler(CouponSoldOutException::class)
    fun handleSoldOut(ex: CouponSoldOutException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("COUPON_SOLD_OUT", ex.message ?: "Coupon sold out"))

    @ExceptionHandler(DuplicateIssueException::class)
    fun handleDuplicate(ex: DuplicateIssueException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("DUPLICATE_ISSUE", ex.message ?: "Already issued"))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("DUPLICATE_ISSUE", "Already issued"))

    @ExceptionHandler(CouponNotYetOpenException::class)
    fun handleNotYetOpen(ex: CouponNotYetOpenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("COUPON_NOT_YET_OPEN", ex.message ?: "Coupon not yet open"))
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest" --rerun`
Expected: 5개 테스트(기존 4개 + 신규 1개) 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt \
        coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt
git commit -m "feat: map CouponNotYetOpenException to HTTP 403"
```

---

### Task 3: 동시성 테스트에 `openAt` 명시 + 전체 스위트 최종 검증

**Files:**
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`

**Interfaces:**
- Consumes: `Coupon(name, totalQuantity, issuedQuantity, openAt)` (Task 1에서 확장된 생성자)

**배경:** `Coupon.openAt`의 기본값이 `LocalDateTime.now()`라, 이 테스트를 아무것도 안 고쳐도 이미 통과한다(생성 시점이 곧 "이미 열림"이므로). 그래도 "이 테스트는 이미 오픈된 쿠폰을 가정한다"는 의도를 코드에 명시적으로 남겨서, 나중에 기본값이 바뀌거나 이 테스트만 따로 읽는 사람이 오해하지 않게 한다.

- [ ] **Step 1: `openAt`을 과거 시각으로 명시**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`의 import 블록에 추가:

```kotlin
import java.time.LocalDateTime
```

```kotlin
        val coupon = couponRepository.save(Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 0))
```
를
```kotlin
        val coupon = couponRepository.save(
            Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 0, openAt = LocalDateTime.now().minusMinutes(1)),
        )
```
로 변경.

- [ ] **Step 2: 동시성 테스트 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceConcurrencyTest" --rerun`
Expected: PASS — `성공=1, 실패=29, 최종 issuedQuantity=1` (Task 1 이전과 동일한 결과)

- [ ] **Step 3: 전체 테스트 스위트 실행**

Run: `./gradlew test --rerun`
Expected: 전체 PASS. 특히 `CouponControllerTest`, `CouponRepositoryTest`는 `Coupon(...)`을 3-인자로 생성하는데 `openAt`에 기본값이 있어 그대로 컴파일·통과해야 한다(수정 불필요).

- [ ] **Step 4: 쿼리 로그로 오픈 전 요청이 락을 안 거는지 확인 (선택, 수동 검증)**

Run: `./gradlew bootRun` 후 `openAt`을 미래로 설정한 쿠폰에 `POST /api/coupons/{id}/issue` 요청 → 콘솔에 `select ... for update`가 안 찍히고 `select` 한 번만 나가는지 확인. (자동화된 테스트로 검증하기엔 쿼리 로그 파싱이 과하므로 수동 확인만 하고 자동 테스트는 추가하지 않는다 — YAGNI)

- [ ] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt
git commit -m "test: make CouponServiceConcurrencyTest's openAt assumption explicit"
```
