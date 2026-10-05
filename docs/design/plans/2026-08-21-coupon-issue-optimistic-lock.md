# 쿠폰 발급 낙관적 락(@Version) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `CouponCampaign`에 `@Version`을 추가하고, 커밋 시점 충돌(`ObjectOptimisticLockingFailureException`)을 재시도-백오프로 처리하는 `issueOptimistic` 메서드 + `POST /api/coupons/{campaignId}/issue-optimistic` 엔드포인트를 추가한다.

**Architecture:** 기존 `completeIssue`(재고 체크→증가→저장) 헬퍼를 그대로 재사용하고, 그 바깥에 재시도 루프를 씌운다. 재시도마다 캠페인을 다시 조회해 최신 버전을 얻고, `completeIssue` 직후 `couponCampaignRepository.flush()`를 명시적으로 호출해 버전 충돌을 `try` 블록 안에서 즉시 드러낸다(자세한 이유는 스펙 문서 참고).

**Tech Stack:** Kotlin, Spring Boot 4.1.0, Spring Data JPA, Hibernate, MySQL(로컬), JUnit 5 + Mockito(서비스 단위 테스트), `@DataJpaTest`(리포지토리 테스트, 실제 로컬 MySQL 사용), `@SpringBootTest`(컨트롤러/동시성 테스트)

**Spec:** `docs/design/specs/2026-08-20-coupon-issue-optimistic-lock-design.md`

## Global Constraints

- 새 의존성 추가 금지 — Spring Retry 등 프레임워크 없이 서비스 메서드 안에 직접 재시도 루프를 구현한다(스펙 결정 사항).
- 재시도 파라미터: 고정 횟수 3회, 고정 지연 20ms.
- 재시도 소진 시 새 예외 `CouponIssueConflictException` → HTTP 409, 코드 `COUPON_ISSUE_CONFLICT`.
- 기존 `completeIssue`/`ensureNotAlreadyIssued`/`ensureStockAvailable`/`incrementIssuedQuantity`/`saveIssue` 헬퍼는 시그니처를 바꾸지 않고 그대로 재사용한다 — 비관적 락/락없음 경로에 영향 없어야 한다.
- `CouponService` 생성자에 새 파라미터를 추가할 땐 기존 위치(끝)에 기본값 있는 파라미터로 추가한다 — 기존 테스트의 `CouponService(repoA, repoB, repoC)` / `CouponService(repoA, repoB, repoC, fixedClock)` 호출부가 깨지면 안 된다.

---

## Task 1: `CouponCampaign`에 `@Version` 필드 추가

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponCampaign.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponCampaignRepositoryTest.kt`

**Interfaces:**
- Produces: `CouponCampaign.version: Long` (읽기 전용처럼 다뤄야 함 — Hibernate가 관리, 애플리케이션 코드에서 직접 증가시키지 않는다). 이후 Task 3에서 이 필드의 존재만으로 낙관적 락이 활성화된다(엔티티에 `@Version`이 있으면 Hibernate가 자동으로 UPDATE에 버전 체크를 건다).

- [ ] **Step 1: 실패하는 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponCampaignRepositoryTest.kt`에 아래 테스트를 추가한다. `TestEntityManager`를 새로 주입받아야 한다(같은 트랜잭션 안에서 `findById`를 두 번 부르면 Hibernate 1차 캐시가 같은 인스턴스를 돌려줘서 "서로 다른 두 세션이 같은 행을 읽었다"를 재현할 수 없으므로, `clear()`로 영속성 컨텍스트를 비워가며 두 개의 독립된 인스턴스를 만든다).

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.orm.ObjectOptimisticLockingFailureException

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponCampaignRepositoryTest {

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Autowired
    lateinit var testEntityManager: TestEntityManager

    @Test
    fun `쿠폰 캠페인을 저장하고 조회할 수 있다`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))

        val saved = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 10, issuedQuantity = 0),
        )

        val found = couponCampaignRepository.findById(saved.id!!).orElseThrow()

        assertEquals(template.id, found.couponTemplateId)
        assertEquals(10, found.totalQuantity)
        assertEquals(0, found.issuedQuantity)
    }

    @Test
    fun `서로 다른 세션이 같은 행을 읽고 나중에 저장을 시도하면 낙관적 락 예외가 발생한다`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        val saved = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 10, issuedQuantity = 0),
        )
        testEntityManager.flush()
        testEntityManager.clear()

        val first = couponCampaignRepository.findById(saved.id!!).orElseThrow()
        testEntityManager.clear()
        val second = couponCampaignRepository.findById(saved.id!!).orElseThrow()

        first.issuedQuantity += 1
        couponCampaignRepository.saveAndFlush(first)

        second.issuedQuantity += 1
        assertThrows(ObjectOptimisticLockingFailureException::class.java) {
            couponCampaignRepository.saveAndFlush(second)
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.repository.CouponCampaignRepositoryTest"`
Expected: FAIL — 새 테스트가 컴파일 에러(또는 `ObjectOptimisticLockingFailureException`이 안 던져져서 실패)로 실패한다. `CouponCampaign`에 `version` 필드가 없어서 `@Version`이 없으므로, `saveAndFlush`가 그냥 조용히 두 번 다 성공해버려서 `assertThrows`가 실패하는 형태일 것이다.

- [ ] **Step 3: `@Version` 필드 추가**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponCampaign.kt`를 아래로 전체 교체한다.

```kotlin
package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Version
import java.time.LocalDateTime

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

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.repository.CouponCampaignRepositoryTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponCampaign.kt coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponCampaignRepositoryTest.kt
git commit -m "feat: CouponCampaign에 @Version 필드 추가"
```

---

## Task 2: `CouponIssueConflictException` 추가 및 409 매핑

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`

**Interfaces:**
- Consumes: 없음(신규 예외 타입).
- Produces: `CouponIssueConflictException(couponId: Long, cause: Throwable? = null)`. `GlobalExceptionHandler.handleIssueConflict(ex: CouponIssueConflictException): ResponseEntity<ErrorResponse>` — Task 3(서비스)과 Task 4(컨트롤러 통합 테스트)가 이 예외 타입과 응답 코드(`COUPON_ISSUE_CONFLICT`)를 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`GlobalExceptionHandlerTest.kt`에 아래 테스트를 추가한다.

```kotlin
    @Test
    fun `낙관적 락 재시도가 소진되면 409를 반환한다`() {
        val response = handler.handleIssueConflict(CouponIssueConflictException(1L))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("COUPON_ISSUE_CONFLICT", response.body?.code)
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest"`
Expected: FAIL — `CouponIssueConflictException`과 `handleIssueConflict`가 아직 없어서 컴파일 에러.

- [ ] **Step 3: 예외 클래스 추가**

`CouponExceptions.kt` 맨 끝에 추가:

```kotlin

class CouponIssueConflictException(couponId: Long, cause: Throwable? = null) :
    RuntimeException("재시도 소진: campaign=$couponId", cause)
```

- [ ] **Step 4: 핸들러 추가**

`GlobalExceptionHandler.kt`의 `handleNotYetOpen` 메서드 뒤(클래스 닫는 `}` 직전)에 추가:

```kotlin

    @ExceptionHandler(CouponIssueConflictException::class)
    fun handleIssueConflict(ex: CouponIssueConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("COUPON_ISSUE_CONFLICT", ex.message ?: "Concurrent modification conflict"))
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest"`
Expected: PASS (6 tests)

- [ ] **Step 6: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt
git commit -m "feat: 낙관적 락 재시도 소진 예외(CouponIssueConflictException) 추가"
```

---

## Task 3: `CouponService.issueOptimistic` 구현

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`

**Interfaces:**
- Consumes: `CouponIssueConflictException`(Task 2), `CouponCampaign.version`(Task 1, 직접 참조는 안 하지만 존재해야 Hibernate가 버전 체크를 함).
- Produces: `CouponService(..., optimisticMaxAttempts: Int = 3)` 생성자, `fun issueOptimistic(couponCampaignId: Long, userId: Long): CouponIssue`. Task 4(컨트롤러), Task 5(동시성 테스트)가 이 메서드 시그니처와 `optimisticMaxAttempts` 생성자 파라미터를 그대로 사용한다.

**알려진 리스크 (구현 중 반드시 확인할 것):** 재시도 2번째 attempt에서 예외가 안 잡히거나, `flush()` 실패 뒤 다음 `findById`가 이상하게 동작하면 — Hibernate가 flush 실패 후 같은 트랜잭션의 세션을 더 이상 정상적으로 못 쓰게 만드는 경우일 수 있다. 이 경우 Task 5의 동시성 테스트가 재현해줄 것이다. 만약 그런 징후가 보이면 구현을 멈추고 사람에게 보고한다 — 가능한 해법(예: 각 attempt를 `REQUIRES_NEW`로 새 트랜잭션에서 실행)은 스펙에 없는 새 설계 결정이라 임의로 바꾸지 않는다.

**왜 `completeIssue` 직후 `couponCampaignRepository.flush()`를 명시적으로 불러야 하는가:** `@Transactional`은 프록시가 메서드 실행을 감싸는 방식이라, 실제 커밋(과 그 직전의 flush)은 메서드 바디가 끝나고 프록시로 제어가 돌아온 뒤 일어난다. `incrementIssuedQuantity`의 `save()`는 기본적으로 변경 사항을 큐에 쌓아둘 뿐, 즉시 `UPDATE`를 보내지 않는다. `flush()`를 명시적으로 안 부르면, 버전 충돌을 감지하는 실제 `UPDATE ... WHERE version = ?`가 `issueOptimistic` 메서드가 `return`한 이후(프록시가 트랜잭션을 커밋하는 시점)에야 실행된다. 그 시점엔 이미 이 메서드의 `try/catch`를 벗어난 뒤라서, `ObjectOptimisticLockingFailureException`이 재시도 루프에 전혀 안 잡히고 그대로 호출자에게 새 나간다 — 재시도 로직 자체가 유명무실해진다. `completeIssue` 호출 직후, `return`하기 전에(아직 `try` 블록 안에서) `flush()`를 명시적으로 불러야, 버전 충돌이 그 자리에서 즉시 터지고 `catch`가 잡을 수 있다. 기존 `incrementIssuedQuantity`(비관적 락/락없음과 공유하는 헬퍼)는 그대로 둔다 — `flush()`는 `issueOptimistic` 안에서만 필요하다.

- [ ] **Step 1: 실패하는 테스트 작성**

`CouponServiceTest.kt`의 `import` 블록에 아래 두 줄을 추가한다(`Mockito.doThrow`, `CouponIssueConflictException`, `ObjectOptimisticLockingFailureException`):

```kotlin
import com.coffee_coupon_api.exception.CouponIssueConflictException
import org.mockito.Mockito.doThrow
import org.springframework.orm.ObjectOptimisticLockingFailureException
```

`존재하지 않는 쿠폰을 조회하면 CouponNotFoundException이 발생한다` 테스트 바로 뒤(파일 끝 `}` 앞)에 아래 테스트들을 추가한다.

```kotlin

    @Test
    fun `issueOptimistic - 발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issueOptimistic(1L, 100L)

        assertEquals(1, campaign.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `issueOptimistic - 존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issueOptimistic(999L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`() {
        val openAt = LocalDateTime.of(2026, 8, 14, 10, 0)
        val threeSecondsBeforeOpen = openAt.minusSeconds(3).atZone(ZoneId.systemDefault()).toInstant()
        val fixedClock = Clock.fixed(threeSecondsBeforeOpen, ZoneId.systemDefault())
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueRepository, fixedClock)
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0, openAt = openAt)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))

        assertThrows(CouponNotYetOpenException::class.java) {
            serviceWithFixedClock.issueOptimistic(1L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issueOptimistic(1L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issueOptimistic(1L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 버전 충돌이 1회 발생해도 재시도로 결국 성공한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)
        doThrow(ObjectOptimisticLockingFailureException(CouponCampaign::class.java, 1L))
            .doNothing()
            .`when`(couponCampaignRepository).flush()

        val result = couponService.issueOptimistic(1L, 100L)

        assertEquals(100L, result.userId)
    }

    @Test
    fun `issueOptimistic - 재시도를 다 써도 계속 충돌하면 CouponIssueConflictException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        val serviceWithOneAttempt = CouponService(
            couponCampaignRepository,
            couponTemplateRepository,
            couponIssueRepository,
            optimisticMaxAttempts = 1,
        )
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        doThrow(ObjectOptimisticLockingFailureException(CouponCampaign::class.java, 1L))
            .`when`(couponCampaignRepository).flush()

        assertThrows(CouponIssueConflictException::class.java) {
            serviceWithOneAttempt.issueOptimistic(1L, 100L)
        }
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest"`
Expected: FAIL — `issueOptimistic` 메서드와 `optimisticMaxAttempts` 생성자 파라미터가 아직 없어서 컴파일 에러.

- [ ] **Step 3: `issueOptimistic` 구현**

`CouponService.kt`를 아래로 전체 교체한다.

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.exception.CouponIssueConflictException
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.time.Clock
import java.time.LocalDateTime
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val OPTIMISTIC_RETRY_DELAY_MS = 20L

@Service
class CouponService(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val optimisticMaxAttempts: Int = 3,
) {

    @Transactional
    fun issuePessimistic(couponCampaignId: Long, userId: Long): CouponIssue {
        val openAt = couponCampaignRepository.findOpenAtById(couponCampaignId)
            ?: throw CouponNotFoundException(couponCampaignId)

        if (LocalDateTime.now(clock).isBefore(openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, openAt)
        }

        val campaign = couponCampaignRepository.findByIdForUpdate(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }

        return completeIssue(campaign, couponCampaignId, userId)
    }

    @Transactional
    fun issueNoLock(couponCampaignId: Long, userId: Long): CouponIssue {
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }

        if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
        }

        return completeIssue(campaign, couponCampaignId, userId)
    }

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
                couponCampaignRepository.flush()
                return result
            } catch (e: ObjectOptimisticLockingFailureException) {
                lastException = e
                if (attempt < optimisticMaxAttempts) Thread.sleep(OPTIMISTIC_RETRY_DELAY_MS)
            }
        }
        throw CouponIssueConflictException(couponCampaignId, lastException)
    }

    private fun completeIssue(campaign: CouponCampaign, couponCampaignId: Long, userId: Long): CouponIssue {
        ensureNotAlreadyIssued(couponCampaignId, userId)
        ensureStockAvailable(campaign, couponCampaignId)
        incrementIssuedQuantity(campaign)
        return saveIssue(couponCampaignId, userId)
    }

    private fun ensureNotAlreadyIssued(couponCampaignId: Long, userId: Long) {
        if (couponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId, userId)) {
            throw DuplicateIssueException(couponCampaignId, userId)
        }
    }

    private fun ensureStockAvailable(campaign: CouponCampaign, couponCampaignId: Long) {
        if (campaign.issuedQuantity >= campaign.totalQuantity) {
            throw CouponSoldOutException(couponCampaignId)
        }
    }

    private fun incrementIssuedQuantity(campaign: CouponCampaign) {
        campaign.issuedQuantity += 1
        couponCampaignRepository.save(campaign)
    }

    private fun saveIssue(couponCampaignId: Long, userId: Long): CouponIssue {
        return couponIssueRepository.save(CouponIssue(couponCampaignId = couponCampaignId, userId = userId))
    }

    @Transactional(readOnly = true)
    fun getCoupon(couponCampaignId: Long): Pair<CouponCampaign, CouponTemplate> {
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }
        val template = couponTemplateRepository.findById(campaign.couponTemplateId)
            .orElseThrow { CouponNotFoundException(campaign.couponTemplateId) }
        return campaign to template
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest"`
Expected: PASS (모든 테스트, 기존 것 포함)

- [ ] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt
git commit -m "feat: 낙관적 락 기반 issueOptimistic 구현 (재시도-백오프 포함)"
```

---

## Task 4: 컨트롤러 엔드포인트 추가

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt`

**Interfaces:**
- Consumes: `CouponService.issueOptimistic(couponCampaignId: Long, userId: Long): CouponIssue`(Task 3), `CouponIssue.toResponse()`(기존 확장 함수, 변경 없음).
- Produces: `POST /api/coupons/{campaignId}/issue-optimistic` 엔드포인트.

- [ ] **Step 1: 실패하는 테스트 작성**

`CouponControllerTest.kt`의 `쿠폰 잔여 수량을 조회한다` 테스트 앞(아무 위치나 가능, 여기서는 `issue-no-lock` 테스트 뒤)에 추가한다.

```kotlin

    @Test
    fun `issue-optimistic - 쿠폰을 정상적으로 발급받는다`() {
        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue-optimistic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(1))
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.controller.CouponControllerTest"`
Expected: FAIL — `/issue-optimistic` 엔드포인트가 없어서 404.

- [ ] **Step 3: 엔드포인트 추가**

`CouponController.kt`의 `issueNoLock` 함수 바로 뒤(`getCoupon` 함수 앞)에 추가한다.

```kotlin

    @Operation(
        summary = "쿠폰 발급 (낙관적 락)",
        description = "지정한 쿠폰을 사용자에게 발급한다. `@Version` 기반으로 커밋 시점 충돌을 감지하고, " +
            "충돌 시 최대 3회까지 재조회 후 재시도한다. 재시도를 다 써도 충돌하면 409를 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "403",
            description = "아직 오픈되지 않은 쿠폰(COUPON_NOT_YET_OPEN)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "쿠폰을 찾을 수 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "쿠폰 소진(COUPON_SOLD_OUT), 중복 발급(DUPLICATE_ISSUE), " +
                "또는 재시도 소진으로 인한 동시 수정 충돌(COUPON_ISSUE_CONFLICT)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{couponId}/issue-optimistic")
    fun issueOptimistic(
        @Parameter(description = "발급할 쿠폰 ID") @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        return couponService.issueOptimistic(couponId, request.userId).toResponse()
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.controller.CouponControllerTest"`
Expected: PASS (모든 테스트, 기존 것 포함)

- [ ] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt
git commit -m "feat: POST /api/coupons/{id}/issue-optimistic 엔드포인트 추가"
```

---

## Task 5: 동시성 재현 테스트 — 재시도 유무 비교

**Files:**
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`

**Interfaces:**
- Consumes: `CouponService.issueOptimistic`(Task 3), `CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueRepository, clock, optimisticMaxAttempts)` 생성자(Task 3).

이 태스크는 Issue #5가 요구한 "재시도 유무에 따른 성공/실패 횟수 관찰·검증"을 실제로 만든다. **재고 1개가 아니라 10개, 동시 요청 30건**으로 시나리오를 잡는다 — 재고가 1개면 재시도 여부와 무관하게 항상 성공은 최대 1명이라 차이가 안 보인다. 재고 10개짜리에 30명이 몰려야, "재시도가 있으면 10명 다 채워지고 재시도가 없으면 언더셀난다"는 차이가 숫자로 드러난다.

- [ ] **Step 1: 실패하는 테스트 작성**

`CouponServiceConcurrencyTest.kt`에서 클래스 상단 필드 목록에 `couponIssueRepository`와 `clock`을 추가로 주입받는다(현재 `couponService`/`couponCampaignRepository`/`couponTemplateRepository`만 있음).

```kotlin
    @Autowired
    lateinit var couponIssueRepository: CouponIssueRepository

    @Autowired
    lateinit var clock: Clock
```

파일 상단 import에 아래 두 줄을 추가한다.

```kotlin
import com.coffee_coupon_api.repository.CouponIssueRepository
import java.time.Clock
```

`락이 없으면 재고 1개짜리 쿠폰에 N명이 동시에 요청할 때 과발급된다` 테스트 뒤(`seedOpenCampaign` private 함수 앞)에 아래 두 테스트를 추가한다.

```kotlin

    @Test
    fun `낙관적 락(재시도 3회)은 재고 10개짜리 쿠폰에 30명이 몰려도 딱 10명만 성공하고 과발급되지 않는다`() {
        val campaign = seedOpenCampaign(totalQuantity = 10)
        val threadCount = 30
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runConcurrently(threadCount) { i ->
            try {
                couponService.issueOptimistic(campaign.id!!, userId = i.toLong())
                successCount.incrementAndGet()
            } catch (e: Exception) {
                failCount.incrementAndGet()
            }
        }

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[낙관적 락, 재시도 3회] totalQuantity=10, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        assertEquals(10, successCount.get())
        assertEquals(threadCount - 10, failCount.get())
        assertEquals(10, finalCampaign.issuedQuantity)
    }

    @Test
    fun `낙관적 락 재시도가 없으면(1회) 재고 10개가 남아있어도 언더셀이 날 수 있다`() {
        val campaign = seedOpenCampaign(totalQuantity = 10)
        val threadCount = 30
        val noRetryService = CouponService(
            couponCampaignRepository,
            couponTemplateRepository,
            couponIssueRepository,
            clock,
            optimisticMaxAttempts = 1,
        )
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runConcurrently(threadCount) { i ->
            try {
                noRetryService.issueOptimistic(campaign.id!!, userId = i.toLong())
                successCount.incrementAndGet()
            } catch (e: Exception) {
                failCount.incrementAndGet()
            }
        }

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[낙관적 락, 재시도 없음] totalQuantity=10, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity} " +
                "(재시도 3회 버전과 비교: 재고가 남았는데도 10명을 다 못 채웠다면 언더셀 — 과발급이 아니라 낙관적 락의 특성)",
        )

        assertTrue(successCount.get() <= 10, "재고 10개인데 10명 넘게 성공했다 — 과발급 발생, 낙관적 락 자체가 깨진 것")
        assertEquals(finalCampaign.issuedQuantity, successCount.get())
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceConcurrencyTest"`
Expected: 새 테스트 2개가 컴파일 에러(아직 `issueOptimistic`이 없다면) 또는 어설션 실패로 FAIL. Task 3이 이미 완료된 상태라면 컴파일은 되고, `couponCampaignRepository.flush()`가 없었다면(=Task 3을 건너뛰고 이 태스크만 먼저 봤다면) 재시도 3회 테스트의 `assertEquals(10, successCount.get())`가 실패할 것이다 — 이게 Task 3에서 설명한 "flush 없으면 재시도가 무력화된다"를 실측으로 보여주는 지점이다.

- [ ] **Step 3: 통과 확인 (Task 3에서 이미 구현했으므로 추가 구현 불필요)**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceConcurrencyTest"`
Expected: PASS (4 tests: 기존 2개 + 신규 2개). 만약 재시도 3회 테스트가 여기서 실패한다면(예: `successCount`가 10이 안 됨), Task 3의 "알려진 리스크"(Hibernate 세션이 flush 실패 후 이상 동작)가 실제로 발생한 것이니 **구현을 멈추고 사람에게 보고한다.**

- [ ] **Step 4: 커밋**

```bash
git add coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt
git commit -m "test: 낙관적 락 재시도 유무에 따른 성공/실패 횟수 비교 재현 테스트 추가"
```

---

## Task 6: 전체 테스트 스위트 재확인 + 브랜치 마무리

**Files:** 없음(검증 전용 태스크)

- [ ] **Step 1: 전체 테스트 실행**

Run: `cd coffee-coupon-api && ./gradlew test`
Expected: 전체 PASS. 특히 `issuePessimistic`/`issueNoLock` 관련 기존 테스트가 하나도 안 깨졌는지 확인 — `completeIssue`/`incrementIssuedQuantity`를 안 건드렸으므로 영향 없어야 정상이다.

- [ ] **Step 2: `finishing-a-development-branch` 스킬로 브랜치 마무리**

REQUIRED SUB-SKILL: `superpowers:finishing-a-development-branch`를 사용해 테스트 확인, 옵션 제시, 선택 실행까지 진행한다.
