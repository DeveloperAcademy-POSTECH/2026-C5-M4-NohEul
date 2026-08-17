# 오픈 시각 기반 쿠폰 발급 (CouponTemplate/CouponCampaign 분리) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 지금 `coupon`/`coupon_issue` 2-테이블 스키마를 `coupon_template`(쿠폰 종류) → `coupon_campaign`(이번 발급, 기존 `coupon`) → `coupon_issue`(발급 이력) 3-테이블로 분리하고, `coupon_campaign`에 오픈 시각(`openAt`)을 추가해 오픈 전 발급 요청을 비관적 락을 걸기 전에 거부한다.

**Architecture:** `CouponService.issue()`에서 락 없는 조회로 먼저 `openAt`만 확인해 오픈 전이면 즉시 거부하고, 오픈 후에만 기존 `findByIdForUpdate`(`PESSIMISTIC_WRITE`)로 넘어간다. 시간 판단은 생성자로 주입한 `Clock`을 통해서만 해서 테스트에서 결정론적으로 제어한다. `CouponTemplate`↔`CouponCampaign`↔`CouponIssue` 사이의 관계는 진짜 FK 제약이나 JPA 연관관계 없이 `Long` 값 컬럼으로만 표현한다(이번 단계 범위 밖).

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, Mockito(단위 테스트), `@DataJpaTest`(리포지토리 테스트), `@SpringBootTest`(컨트롤러/동시성 테스트)

## Global Constraints

- 스펙 문서: `docs/superpowers/specs/2026-08-13-coupon-scheduled-open-design.md` (2026-08-14 개정판)
- 브랜치: `feature/coupon-issue-pessimistic-lock`(`dd8e2f1`, `findByIdForUpdate`+`PESSIMISTIC_WRITE` 락 포함) 위에서 시작한 `feature/coupon-scheduled-open`
- 오픈 전 요청 응답: HTTP 403, 에러 코드 `COUPON_NOT_YET_OPEN`
- `CouponController`/`CouponService` 클래스명과 API 경로(`/api/coupons/...`)는 그대로 유지한다 — 엔티티/리포지토리 계층만 분리한다
- 연관관계는 `Long` 값 컬럼으로만 표현한다. `@ManyToOne`/DB FK 제약 승격은 범위 밖
- 할인율 외의 할인 형태(정액 등)는 다루지 않는다 — `CouponTemplate.discountRate: Int` 하나만 둔다
- 기존 `CouponServiceConcurrencyTest`(재고 1개, 동시 요청 30개 → 성공 1명)는 최종적으로 계속 통과해야 한다

---

### Task 1: `CouponTemplate` 엔티티 + 리포지토리

**Files:**
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponTemplate.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponTemplateRepository.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponTemplateRepositoryTest.kt`

**Interfaces:**
- Produces: `CouponTemplate(name: String, discountRate: Int)` — Task 2부터 `CouponCampaign`이 `couponTemplateId`로 참조. `CouponTemplateRepository : JpaRepository<CouponTemplate, Long>` — Task 2에서 `CouponService`가 소비.

이 태스크는 기존 코드를 하나도 건드리지 않는 순수 추가라, TDD 사이클 없이 바로 구현 + 리포지토리 테스트로 검증한다(엔티티/리포지토리 자체엔 분기 로직이 없어 "실패하는 테스트"를 먼저 쓸 대상이 없다).

- [x] **Step 1: `CouponTemplate` 엔티티 생성**

```kotlin
package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
class CouponTemplate(
    var name: String,
    var discountRate: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
```

- [x] **Step 2: `CouponTemplateRepository` 생성**

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface CouponTemplateRepository : JpaRepository<CouponTemplate, Long>
```

- [x] **Step 3: 리포지토리 테스트 작성**

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponTemplateRepositoryTest {

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Test
    fun `쿠폰 템플릿을 저장하고 조회할 수 있다`() {
        val saved = couponTemplateRepository.save(CouponTemplate(name = "아메리카노 10% 할인", discountRate = 10))

        val found = couponTemplateRepository.findById(saved.id!!).orElseThrow()

        assertEquals("아메리카노 10% 할인", found.name)
        assertEquals(10, found.discountRate)
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.repository.CouponTemplateRepositoryTest" --rerun`
Expected: PASS

- [x] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponTemplate.kt \
        coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponTemplateRepository.kt \
        coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponTemplateRepositoryTest.kt
git commit -m "feat: add CouponTemplate entity and repository"
```

---

### Task 2: `Coupon` → `CouponCampaign` 스키마 개명 + 서비스/컨트롤러 배선

**Files:**
- Delete: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponCampaign.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponIssue.kt`
- Delete: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponRepository.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponCampaignRepository.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponIssueRepository.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt`
- Delete: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponRepositoryTest.kt`
- Create: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponCampaignRepositoryTest.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponIssueRepositoryTest.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt`

**Interfaces:**
- Consumes: `CouponTemplate`/`CouponTemplateRepository` (Task 1)
- Produces:
  - `CouponCampaign(couponTemplateId: Long, totalQuantity: Int, issuedQuantity: Int = 0, openAt: LocalDateTime = LocalDateTime.now())`
  - `CouponIssue(couponCampaignId: Long, userId: Long, issuedAt: LocalDateTime = LocalDateTime.now())` (필드명만 변경, 클래스명 동일)
  - `CouponCampaignRepository.findByIdForUpdate(id: Long): Optional<CouponCampaign>`
  - `CouponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId: Long, userId: Long): Boolean`
  - `CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueRepository)` — Task 3이 여기에 `clock` 파라미터를 추가함
  - `CouponService.issue(couponCampaignId: Long, userId: Long): CouponIssue`
  - `CouponService.getCoupon(couponCampaignId: Long): Pair<CouponCampaign, CouponTemplate>`

이 태스크는 **동작을 하나도 안 바꾸는 순수 리네임/구조 분리**다. 새로운 분기 로직이 없어서 TDD 레드-그린 사이클이 자연스럽지 않다 — 대신 "고치고 → 전체 스위트가 그린인지 확인"으로 검증한다. 파일이 많지만 전부 기계적인 개명이라, 하나라도 놓치면 컴파일이 안 돼서 바로 드러난다.

- [x] **Step 1: `Coupon.kt` 삭제, `CouponCampaign.kt` 생성**

```bash
rm coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt
```

```kotlin
package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
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
}
```

(`openAt`은 이 태스크에서 필드만 추가해둔다 — 실제로 이 값을 읽어서 막는 가드 절은 Task 3에서 추가한다.)

- [x] **Step 2: `CouponIssue.couponId` → `couponCampaignId` 개명**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponIssue.kt` 전체를 다음으로 교체:

```kotlin
package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "coupon_issue",
    uniqueConstraints = [UniqueConstraint(columnNames = ["coupon_campaign_id", "user_id"])],
)
class CouponIssue(
    var couponCampaignId: Long,
    var userId: Long,
    var issuedAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
```

- [x] **Step 3: `CouponRepository.kt` 삭제, `CouponCampaignRepository.kt` 생성**

```bash
rm coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponRepository.kt
```

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import jakarta.persistence.LockModeType
import java.util.Optional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CouponCampaignRepository : JpaRepository<CouponCampaign, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponCampaign c where c.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<CouponCampaign>
}
```

- [x] **Step 4: `CouponIssueRepository` 메서드 개명**

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponIssue
import org.springframework.data.jpa.repository.JpaRepository

interface CouponIssueRepository : JpaRepository<CouponIssue, Long> {
    fun existsByCouponCampaignIdAndUserId(couponCampaignId: Long, userId: Long): Boolean
}
```

- [x] **Step 5: `CouponService` 배선을 새 리포지토리로 교체**

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CouponService(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueRepository: CouponIssueRepository,
) {

    @Transactional
    fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
        val campaign = couponCampaignRepository.findByIdForUpdate(couponCampaignId)
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

    @Transactional(readOnly = true)
    fun getCoupon(couponCampaignId: Long): Pair<CouponCampaign, CouponTemplate> {
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }
        val template = couponTemplateRepository.findById(campaign.couponTemplateId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }
        return campaign to template
    }
}
```

`getCoupon()`이 `Pair<CouponCampaign, CouponTemplate>`을 리턴하는 이유: 응답에 필요한 `name`(할인 정보)은 이제 `CouponTemplate`에만 있어서, 서비스가 두 리포지토리를 조합해줘야 컨트롤러가 DTO 하나로 매핑할 수 있다.

- [x] **Step 6: `CouponController`가 새 반환 타입에 맞게 매핑**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt`에서 `issue` 메서드 본문의 `CouponIssueResponse` 생성부:
```kotlin
        val issue = couponService.issue(couponId, request.userId)
        return CouponIssueResponse(
            couponId = issue.couponId,
            userId = issue.userId,
            issuedAt = issue.issuedAt,
        )
```
를
```kotlin
        val issue = couponService.issue(couponId, request.userId)
        return CouponIssueResponse(
            couponId = issue.couponCampaignId,
            userId = issue.userId,
            issuedAt = issue.issuedAt,
        )
```
로 변경(응답 DTO의 필드명 `couponId`는 API 계약이라 그대로 둔다 — 엔티티 필드명만 바뀐 걸 반영).

`getCoupon` 메서드 전체를:
```kotlin
    @GetMapping("/{couponId}")
    fun getCoupon(@Parameter(description = "조회할 쿠폰 ID") @PathVariable couponId: Long): CouponResponse {
        val coupon = couponService.getCoupon(couponId)
        return CouponResponse(
            id = coupon.id!!,
            name = coupon.name,
            totalQuantity = coupon.totalQuantity,
            issuedQuantity = coupon.issuedQuantity,
            remainingQuantity = coupon.totalQuantity - coupon.issuedQuantity,
        )
    }
```
에서
```kotlin
    @GetMapping("/{couponId}")
    fun getCoupon(@Parameter(description = "조회할 쿠폰 ID") @PathVariable couponId: Long): CouponResponse {
        val (campaign, template) = couponService.getCoupon(couponId)
        return CouponResponse(
            id = campaign.id!!,
            name = template.name,
            totalQuantity = campaign.totalQuantity,
            issuedQuantity = campaign.issuedQuantity,
            remainingQuantity = campaign.totalQuantity - campaign.issuedQuantity,
        )
    }
```
로 변경. (이 파일의 나머지 부분 — `@Tag`, `@Operation`, `@ApiResponses`, import 등 — 은 그대로 둔다.)

- [x] **Step 7: `CouponRepositoryTest.kt` 삭제, `CouponCampaignRepositoryTest.kt` 생성**

```bash
rm coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponRepositoryTest.kt
```

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponCampaignRepositoryTest {

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

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
}
```

- [x] **Step 8: `CouponIssueRepositoryTest` 개명 반영**

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponIssueRepositoryTest {

    @Autowired
    lateinit var couponIssueRepository: CouponIssueRepository

    @Test
    fun `발급 이력을 저장하고 조회할 수 있다`() {
        couponIssueRepository.save(CouponIssue(couponCampaignId = 1L, userId = 100L))

        val exists = couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)

        assertEquals(true, exists)
    }

    @Test
    fun `같은 쿠폰에 같은 사용자를 중복 저장하면 예외가 발생한다`() {
        couponIssueRepository.saveAndFlush(CouponIssue(couponCampaignId = 1L, userId = 100L))

        assertThrows(DataIntegrityViolationException::class.java) {
            couponIssueRepository.saveAndFlush(CouponIssue(couponCampaignId = 1L, userId = 100L))
        }
    }
}
```

- [x] **Step 9: `CouponServiceTest` 전체를 새 스키마로 개명**

전체 파일을 다음으로 교체:

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CouponServiceTest {

    private lateinit var couponCampaignRepository: CouponCampaignRepository
    private lateinit var couponTemplateRepository: CouponTemplateRepository
    private lateinit var couponIssueRepository: CouponIssueRepository
    private lateinit var couponService: CouponService

    @BeforeEach
    fun setUp() {
        couponCampaignRepository = mock(CouponCampaignRepository::class.java)
        couponTemplateRepository = mock(CouponTemplateRepository::class.java)
        couponIssueRepository = mock(CouponIssueRepository::class.java)
        couponService = CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueRepository)
    }

    @Test
    fun `발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issue(1L, 100L)

        assertEquals(1, campaign.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issue(999L, 100L)
        }
    }

    @Test
    fun `이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `존재하는 쿠폰을 조회하면 쿠폰 정보를 반환한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 3)
        val template = CouponTemplate(name = "아메리카노", discountRate = 10)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponTemplateRepository.findById(1L)).thenReturn(Optional.of(template))

        val (resultCampaign, resultTemplate) = couponService.getCoupon(1L)

        assertEquals("아메리카노", resultTemplate.name)
        assertEquals(10, resultCampaign.totalQuantity)
    }

    @Test
    fun `존재하지 않는 쿠폰을 조회하면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.getCoupon(999L)
        }
    }
}
```

- [x] **Step 10: `CouponServiceConcurrencyTest` 개명 반영**

전체 파일을 다음으로 교체:

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class CouponServiceConcurrencyTest {

    @Autowired
    lateinit var couponService: CouponService

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Test
    fun `비관적 락을 걸면 재고 1개짜리 쿠폰에 N명이 동시에 요청해도 1명만 성공한다`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        val campaign = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 1, issuedQuantity = 0),
        )
        val threadCount = 30
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        repeat(threadCount) { i ->
            executor.submit {
                startGate.await()
                try {
                    couponService.issue(campaign.id!!, userId = i.toLong())
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startGate.countDown()
        doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[재현 결과] totalQuantity=1, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        assertEquals(1, successCount.get())
        assertEquals(threadCount - 1, failCount.get())
        assertEquals(1, finalCampaign.issuedQuantity)
    }
}
```

- [x] **Step 11: `CouponControllerTest` 개명 반영**

전체 파일을 다음으로 교체:

```kotlin
package com.coffee_coupon_api.controller

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CouponControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Autowired
    lateinit var couponIssueRepository: CouponIssueRepository

    private lateinit var campaign: CouponCampaign

    @BeforeEach
    fun setUp() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        campaign = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 1, issuedQuantity = 0),
        )
    }

    @Test
    fun `쿠폰을 정상적으로 발급받는다`() {
        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(1))
    }

    @Test
    fun `존재하지 않는 쿠폰은 404를 반환한다`() {
        mockMvc.perform(
            post("/api/coupons/99999/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"))
    }

    @Test
    fun `이미 발급받은 사용자는 409를 반환한다`() {
        couponIssueRepository.save(CouponIssue(couponCampaignId = campaign.id!!, userId = 1L))

        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_ISSUE"))
    }

    @Test
    fun `재고가 소진되면 409를 반환한다`() {
        couponIssueRepository.save(CouponIssue(couponCampaignId = campaign.id!!, userId = 1L))
        campaign.issuedQuantity = 1
        couponCampaignRepository.save(campaign)

        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 2L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"))
    }

    @Test
    fun `쿠폰 잔여 수량을 조회한다`() {
        mockMvc.perform(get("/api/coupons/${campaign.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.remainingQuantity").value(1))
    }

    @Test
    fun `존재하지 않는 쿠폰 조회는 404를 반환한다`() {
        mockMvc.perform(get("/api/coupons/99999"))
            .andExpect(status().isNotFound)
    }
}
```

- [x] **Step 12: 전체 테스트 스위트 실행**

Run: `./gradlew test --rerun`
Expected: 전체 PASS. (Task 1에서 만든 `CouponTemplateRepositoryTest`도 함께 통과해야 한다.)

- [x] **Step 13: 커밋**

```bash
git add -A coffee-coupon-api/src
git commit -m "refactor: split Coupon into CouponTemplate and CouponCampaign"
```

---

### Task 3: `Clock` 주입 + 오픈 전 가드 절

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/config/ClockConfig.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`

**Interfaces:**
- Consumes: Task 2의 `CouponCampaign.openAt`, `CouponService` 생성자
- Produces: `class CouponNotYetOpenException(couponId: Long, openAt: LocalDateTime) : RuntimeException` — Task 4가 `GlobalExceptionHandler`에서 이 예외를 잡아 403으로 매핑함. `CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueRepository, clock: Clock = Clock.systemDefaultZone())`

**참고 — Spring 빈 주입과 Kotlin 기본값의 차이:** `clock: Clock = Clock.systemDefaultZone()`는 순수 Kotlin 코드에서 인자를 생략하고 호출할 때만 적용되는 기본값이다. Spring이 리플렉션으로 생성자를 호출해 빈을 만들 때는 기본값을 인식하지 못하고 `Clock` 타입 빈을 컨텍스트에서 찾으려 하는데, 기본 Spring Boot는 `Clock` 빈을 자동 등록하지 않는다. 그래서 `ClockConfig`로 `Clock` 빈을 직접 등록해야 `@SpringBootTest`(`CouponServiceConcurrencyTest`, `CouponControllerTest`)와 실제 앱 구동이 깨지지 않는다. `CouponServiceTest`(Mockito, `CouponService(...)`를 직접 `new`)는 순수 Kotlin 호출이라 이 빈 없이도 기본값이 그대로 적용된다.

- [x] **Step 1: `CouponNotYetOpenException` 추가 (스캐폴딩)**

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

- [x] **Step 2: `CouponServiceTest`에 실패하는 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`의 import 블록에 아래 4줄 추가:

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
        val openAt = LocalDateTime.of(2026, 8, 14, 10, 0)
        val threeSecondsBeforeOpen = openAt.minusSeconds(3).atZone(ZoneId.systemDefault()).toInstant()
        val fixedClock = Clock.fixed(threeSecondsBeforeOpen, ZoneId.systemDefault())
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueRepository, fixedClock)
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0, openAt = openAt)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))

        assertThrows(CouponNotYetOpenException::class.java) {
            serviceWithFixedClock.issue(1L, 100L)
        }
    }
```

- [x] **Step 3: 새 테스트만 실패하는지 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest" --rerun`
Expected: 컴파일은 되지만(예외 클래스는 Step 1에서 이미 생성) `오픈 시각 이전에 발급 요청하면...` 테스트가 FAIL — `CouponService`가 아직 이 시나리오에서 `couponCampaignRepository.findByIdForUpdate(1L)`을 호출하려다 스텁이 없어 `CouponNotFoundException`을 던지므로, `assertThrows(CouponNotYetOpenException::class.java)`가 다른 예외를 잡아 실패한다.

- [x] **Step 4: `CouponService`에 `Clock` 주입 + 오픈 전 가드 절 구현**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt` 전체를 다음으로 교체:

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.time.Clock
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CouponService(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    @Transactional
    fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
        val openAt = couponCampaignRepository.findOpenAtById(couponCampaignId)
            ?: throw CouponNotFoundException(couponCampaignId)

        if (LocalDateTime.now(clock).isBefore(openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, openAt)
        }

        val campaign = couponCampaignRepository.findByIdForUpdate(couponCampaignId)
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

    @Transactional(readOnly = true)
    fun getCoupon(couponCampaignId: Long): Pair<CouponCampaign, CouponTemplate> {
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }
        val template = couponTemplateRepository.findById(campaign.couponTemplateId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }
        return campaign to template
    }
}
```

**주의: `findOpenAtById`는 엔티티가 아닌 `openAt` 컬럼만 읽는 스칼라 프로젝션이어야 한다.** 처음에는 `couponCampaignRepository.findById(couponCampaignId)`로 `CouponCampaign` 엔티티를 미리 읽는 안을 구현했으나, 같은 트랜잭션 안에서 엔티티를 한 번 영속성 컨텍스트에 올린 뒤 바로 아래에서 `findByIdForUpdate`로 다시 조회하면 SQL은 `SELECT ... FOR UPDATE`로 다시 나가지만 Hibernate가 1차 캐시(identity map)에 이미 올라온 같은 인스턴스를 그대로 반환해버려 비관적 락이 조용히 무력화되는 문제가 있었다. Task 5의 동시성 테스트에서 기대한 "30개 동시 요청 → 1명만 성공"이 아니라 "10명 성공"으로 재현되어 발견했고, 이후 `findById`를 `findOpenAtById` 스칼라 프로젝션으로 교체해 해결했다.

- [x] **Step 5: `Clock` 빈 등록**

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

- [x] **Step 6: 새 테스트는 통과, 나머지 4개는 왜 깨지는지 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest" --rerun`
Expected: `오픈 시각 이전에 발급 요청하면...`은 PASS. 하지만 `발급 가능한 쿠폰은 정상적으로 발급된다`, `이미 발급받은 사용자는 DuplicateIssueException이 발생한다`, `재고가 소진되면 CouponSoldOutException이 발생한다` 3개는 FAIL — `couponCampaignRepository.findByIdForUpdate(1L)`만 스텁해뒀는데 `issue()`가 이제 그 앞에서 `couponCampaignRepository.findById(1L)`을 먼저 호출하기 때문이다(스텁 없는 목은 `Optional.empty()`를 반환 → `CouponNotFoundException`이 먼저 터짐). 예상된 실패다.

- [x] **Step 7: 기존 3개 테스트에 `findById` 스텁 추가**

`발급 가능한 쿠폰은 정상적으로 발급된다`에서:
```kotlin
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
```
를
```kotlin
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
```
로 변경.

`이미 발급받은 사용자는 DuplicateIssueException이 발생한다`에서:
```kotlin
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
```
를
```kotlin
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
```
로 변경.

`재고가 소진되면 CouponSoldOutException이 발생한다`에서:
```kotlin
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
```
를
```kotlin
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
```
로 변경.

(`존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`, `getCoupon` 관련 2개 테스트는 변경 불필요.)

- [x] **Step 8: `CouponServiceTest` 전체 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest" --rerun`
Expected: 8개 테스트(기존 7개 + 신규 1개) 전부 PASS

- [x] **Step 9: 전체 스위트로 `@SpringBootTest` 컨텍스트가 여전히 뜨는지 확인**

Run: `./gradlew test --rerun`
Expected: 전체 PASS. 특히 `CouponServiceConcurrencyTest`, `CouponControllerTest`가 `Clock` 빈 없이 컨텍스트 로딩에 실패하지 않는지 확인하는 게 이 스텝의 핵심이다.

- [x] **Step 10: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt \
        coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt \
        coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/config/ClockConfig.kt \
        coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt
git commit -m "feat: reject coupon issue requests before openAt"
```

---

### Task 4: `GlobalExceptionHandler`에 403 매핑

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`

**Interfaces:**
- Consumes: `CouponNotYetOpenException(couponId: Long, openAt: LocalDateTime)` (Task 3)
- Produces: `GlobalExceptionHandler.handleNotYetOpen(ex: CouponNotYetOpenException): ResponseEntity<ErrorResponse>` — Task 5의 컨트롤러 테스트가 HTTP 계층에서 이 매핑을 검증함

- [x] **Step 1: 실패하는 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`의 import 블록에 추가:

```kotlin
import java.time.LocalDateTime
```

`` `중복 저장으로 인한 무결성 위반이면 409를 반환한다` `` 테스트 아래에 새 테스트 추가:

```kotlin
    @Test
    fun `오픈 전 발급 요청이면 403을 반환한다`() {
        val response = handler.handleNotYetOpen(CouponNotYetOpenException(1L, LocalDateTime.of(2026, 8, 14, 10, 0)))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("COUPON_NOT_YET_OPEN", response.body?.code)
    }
```

- [x] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest" --rerun`
Expected: FAIL — `handleNotYetOpen`이 아직 `GlobalExceptionHandler`에 없어 컴파일 에러(`unresolved reference`)

- [x] **Step 3: 핸들러 구현**

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

- [x] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest" --rerun`
Expected: 5개 테스트(기존 4개 + 신규 1개) 전부 PASS

- [x] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt \
        coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt
git commit -m "feat: map CouponNotYetOpenException to HTTP 403"
```

---

### Task 5: 컨트롤러/동시성 테스트에 오픈 시각 반영 + 전체 최종 검증

**Files:**
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`

**Interfaces:**
- Consumes: `CouponCampaign(..., openAt: LocalDateTime)`, `GlobalExceptionHandler.handleNotYetOpen` (Task 4, 컨트롤러를 통해 간접 검증)

- [x] **Step 1: 컨트롤러 레벨에서 403 케이스 실패하는 테스트 작성**

`CouponControllerTest.kt`의 import 블록에 추가:

```kotlin
import java.time.LocalDateTime
```

`` `쿠폰 잔여 수량을 조회한다` `` 테스트 위에 새 테스트 추가:

```kotlin
    @Test
    fun `오픈 전 쿠폰 발급 요청은 403을 반환한다`() {
        val futureTemplate = couponTemplateRepository.save(CouponTemplate(name = "아이스티", discountRate = 20))
        val futureCampaign = couponCampaignRepository.save(
            CouponCampaign(
                couponTemplateId = futureTemplate.id!!,
                totalQuantity = 10,
                issuedQuantity = 0,
                openAt = LocalDateTime.now().plusHours(1),
            ),
        )

        mockMvc.perform(
            post("/api/coupons/${futureCampaign.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_YET_OPEN"))
    }
```

- [x] **Step 2: 테스트 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.controller.CouponControllerTest" --rerun`
Expected: 전체 PASS (기존 6개 + 신규 1개 = 7개), 첫 실행부터 바로 PASS해야 한다. 오픈 전 가드 절(Task 3)과 403 매핑(Task 4)이 이미 구현돼 있어서, 이 테스트는 새 로직을 만들기 위한 레드-그린 사이클이 아니라 **컨트롤러 → 서비스 → 예외 핸들러 전체 스택이 실제로 연결돼 있는지 확인하는 회귀 테스트**다. 만약 FAIL한다면 Task 3~4의 배선이 어딘가 빠진 것이니 그쪽을 먼저 점검한다.

- [x] **Step 3: 동시성 테스트에 `openAt`을 명시적으로 과거 시각으로 설정**

`CouponServiceConcurrencyTest.kt`의 import 블록에 추가:

```kotlin
import java.time.LocalDateTime
```

```kotlin
        val campaign = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 1, issuedQuantity = 0),
        )
```
를
```kotlin
        val campaign = couponCampaignRepository.save(
            CouponCampaign(
                couponTemplateId = template.id!!,
                totalQuantity = 1,
                issuedQuantity = 0,
                openAt = LocalDateTime.now().minusMinutes(1),
            ),
        )
```
로 변경.

(`Coupon.openAt`의 기본값이 `LocalDateTime.now()`라 이 변경 없이도 이미 통과하지만, "이 테스트는 이미 오픈된 캠페인을 가정한다"는 의도를 코드에 명시적으로 남겨서 나중에 기본값이 바뀌거나 이 테스트만 따로 읽는 사람이 오해하지 않게 한다.)

- [x] **Step 4: 동시성 테스트 통과 확인**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceConcurrencyTest" --rerun`
Expected: PASS — `성공=1, 실패=29, 최종 issuedQuantity=1`

- [x] **Step 5: 전체 테스트 스위트 최종 실행**

Run: `./gradlew test --rerun`
Expected: 전체 PASS (Task 1~5에서 만든 모든 테스트 포함)

- [x] **Step 6: 쿼리 로그로 오픈 전 요청이 락을 안 거는지 확인 (선택, 수동 검증)**

Run: `./gradlew bootRun` 후 `openAt`을 미래로 설정한 캠페인에 `POST /api/coupons/{id}/issue` 요청 → 콘솔에 `for update`가 안 찍히고 `select` 한 번만 나가는지 확인. (자동화된 테스트로 검증하기엔 쿼리 로그 파싱이 과하므로 수동 확인만 하고 자동 테스트는 추가하지 않는다 — YAGNI)

- [x] **Step 7: 커밋**

```bash
git add coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt \
        coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt
git commit -m "test: cover 403 through the full stack and make concurrency test's openAt assumption explicit"
```
