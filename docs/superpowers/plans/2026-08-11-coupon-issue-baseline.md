# Coupon Issue Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a lock-free baseline coffee coupon issuance API (`POST /api/coupons/{couponId}/issue`, `GET /api/coupons/{couponId}`) that intentionally exposes a check-then-act race condition, as a foundation for later comparing pessimistic/optimistic/distributed lock strategies.

**Architecture:** Controller → Service → Repository (Spring Data JPA) → MySQL, three layers, no locking anywhere. `Coupon.issuedQuantity` is the counter later lock strategies will target; `CouponIssue` rows enforce 1-per-user via a DB unique constraint.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.1.0 (Spring Framework 7.0.8), Spring Data JPA, MySQL, JUnit 5 (Jupiter 6.0.3), Mockito 5.23.0, AssertJ — all already declared in `coffee-coupon-api/build.gradle.kts`, no new dependencies needed.

Spec: `docs/superpowers/specs/2026-08-11-coupon-issue-baseline-design.md`

## Global Constraints

- All new Kotlin files live under `coffee-coupon-api/src/{main,test}/kotlin/com/coffee_coupon_api/`.
- `kotlin("plugin.jpa")` and the `allOpen` block (`build.gradle.kts`) already handle no-arg constructors and making `@Entity` classes non-final — do not add `open` manually.
- **Jackson is 3.1.4 and uses the `tools.jackson.*` package root, not `com.fasterxml.jackson.databind.*`.** `ObjectMapper` is `tools.jackson.databind.ObjectMapper`.
- **Spring Boot test-slice annotations moved packages in 4.1.0** (verified against the actual resolved jars in this project):
  - `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
  - `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
  - `@AutoConfigureTestDatabase` (+ nested `Replace` enum) → `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`
  - `@SpringBootTest` is unchanged: `org.springframework.boot.test.context.SpringBootTest`
  - `MockMvc`, `MockMvcRequestBuilders`, `MockMvcResultMatchers` are unchanged: `org.springframework.test.web.servlet.*`
  - Core Spring (`@RestController`, `@Service`, `JpaRepository`, `DataIntegrityViolationException`, `@Transactional`, `jakarta.persistence.*`) are all unchanged from Spring Boot 3.x-era paths.
  - If a test fails to compile with an "unresolved reference" on any Spring/Jackson import, re-check the path against this list before guessing — do not fall back to pre-4.0 paths from memory.
- No H2 or Testcontainers dependency exists in this project — repository/controller tests run against a real local MySQL instance (matches the README's stated prerequisite). Unit tests (service, exception handler) use Mockito and need no database.
- Test naming: use Korean backtick method names (matches this project's existing style), e.g. `` fun `쿠폰을 정상적으로 발급받는다`() ``.
- Run all Gradle commands from `coffee-coupon-api/` (the Gradle project root, not the git repo root).

---

## File Structure

**Main:**
- `src/main/resources/application.yaml` — modify: add MySQL datasource + JPA config
- `src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt` — create
- `src/main/kotlin/com/coffee_coupon_api/repository/CouponRepository.kt` — create
- `src/main/kotlin/com/coffee_coupon_api/domain/CouponIssue.kt` — create
- `src/main/kotlin/com/coffee_coupon_api/repository/CouponIssueRepository.kt` — create
- `src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt` — create (3 exception classes)
- `src/main/kotlin/com/coffee_coupon_api/dto/ErrorResponse.kt` — create
- `src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt` — create
- `src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt` — create
- `src/main/kotlin/com/coffee_coupon_api/dto/CouponDtos.kt` — create (request/response DTOs)
- `src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt` — create

**Test:**
- `src/test/kotlin/com/coffee_coupon_api/repository/CouponRepositoryTest.kt` — create
- `src/test/kotlin/com/coffee_coupon_api/repository/CouponIssueRepositoryTest.kt` — create
- `src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt` — create
- `src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt` — create
- `src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt` — create

---

### Task 1: Datasource config + `Coupon` entity + `CouponRepository`

**Files:**
- Modify: `coffee-coupon-api/src/main/resources/application.yaml`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponRepository.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponRepositoryTest.kt`

**Interfaces:**
- Produces: `Coupon(name: String, totalQuantity: Int, issuedQuantity: Int = 0)` with mutable `var id: Long?`, `var issuedQuantity: Int`. `CouponRepository : JpaRepository<Coupon, Long>`.

- [ ] **Step 1: Add MySQL datasource + JPA config**

Replace the contents of `coffee-coupon-api/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: coffee-coupon-api
  datasource:
    url: jdbc:mysql://localhost:3306/coffee_coupon?createDatabaseIfNotExist=true
    username: root
    password: ${DB_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
```

Adjust `username`/`password` to match your local MySQL, or export `DB_PASSWORD` before running.

- [ ] **Step 2: Write the failing repository test**

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.Coupon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryTest {

    @Autowired
    lateinit var couponRepository: CouponRepository

    @Test
    fun `쿠폰을 저장하고 조회할 수 있다`() {
        val saved = couponRepository.save(Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 0))

        val found = couponRepository.findById(saved.id!!).orElseThrow()

        assertEquals("아메리카노", found.name)
        assertEquals(10, found.totalQuantity)
        assertEquals(0, found.issuedQuantity)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run (from `coffee-coupon-api/`): `./gradlew test --tests "com.coffee_coupon_api.repository.CouponRepositoryTest"`
Expected: FAIL — compile error, `Coupon`/`CouponRepository` unresolved.

- [ ] **Step 4: Implement `Coupon` entity**

```kotlin
package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
class Coupon(
    var name: String,
    var totalQuantity: Int,
    var issuedQuantity: Int = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
```

- [ ] **Step 5: Implement `CouponRepository`**

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.Coupon
import org.springframework.data.jpa.repository.JpaRepository

interface CouponRepository : JpaRepository<Coupon, Long>
```

- [ ] **Step 6: Run test to verify it passes**

Ensure local MySQL is running first. Run: `./gradlew test --tests "com.coffee_coupon_api.repository.CouponRepositoryTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yaml src/main/kotlin/com/coffee_coupon_api/domain/Coupon.kt src/main/kotlin/com/coffee_coupon_api/repository/CouponRepository.kt src/test/kotlin/com/coffee_coupon_api/repository/CouponRepositoryTest.kt
git commit -m "feat: add datasource config and Coupon entity/repository"
```

---

### Task 2: `CouponIssue` entity (unique constraint) + `CouponIssueRepository`

**Files:**
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/domain/CouponIssue.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/repository/CouponIssueRepository.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/repository/CouponIssueRepositoryTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1 directly (independent entity), but shares the same DB/JPA config from Task 1.
- Produces: `CouponIssue(couponId: Long, userId: Long, issuedAt: LocalDateTime = LocalDateTime.now())` with `var id: Long?`. `CouponIssueRepository : JpaRepository<CouponIssue, Long>` with `fun existsByCouponIdAndUserId(couponId: Long, userId: Long): Boolean`.

- [ ] **Step 1: Write the failing repository tests**

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
        couponIssueRepository.save(CouponIssue(couponId = 1L, userId = 100L))

        val exists = couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)

        assertEquals(true, exists)
    }

    @Test
    fun `같은 쿠폰에 같은 사용자를 중복 저장하면 예외가 발생한다`() {
        couponIssueRepository.saveAndFlush(CouponIssue(couponId = 1L, userId = 100L))

        assertThrows(DataIntegrityViolationException::class.java) {
            couponIssueRepository.saveAndFlush(CouponIssue(couponId = 1L, userId = 100L))
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.coffee_coupon_api.repository.CouponIssueRepositoryTest"`
Expected: FAIL — compile error, `CouponIssue`/`CouponIssueRepository` unresolved.

- [ ] **Step 3: Implement `CouponIssue` entity**

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
    uniqueConstraints = [UniqueConstraint(columnNames = ["coupon_id", "user_id"])],
)
class CouponIssue(
    var couponId: Long,
    var userId: Long,
    var issuedAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
```

- [ ] **Step 4: Implement `CouponIssueRepository`**

```kotlin
package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponIssue
import org.springframework.data.jpa.repository.JpaRepository

interface CouponIssueRepository : JpaRepository<CouponIssue, Long> {
    fun existsByCouponIdAndUserId(couponId: Long, userId: Long): Boolean
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.coffee_coupon_api.repository.CouponIssueRepositoryTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/coffee_coupon_api/domain/CouponIssue.kt src/main/kotlin/com/coffee_coupon_api/repository/CouponIssueRepository.kt src/test/kotlin/com/coffee_coupon_api/repository/CouponIssueRepositoryTest.kt
git commit -m "feat: add CouponIssue entity with unique constraint and repository"
```

---

### Task 3: Exceptions + `ErrorResponse` + `GlobalExceptionHandler`

**Files:**
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/dto/ErrorResponse.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`

**Interfaces:**
- Produces: `CouponNotFoundException(couponId: Long)`, `CouponSoldOutException(couponId: Long)`, `DuplicateIssueException(couponId: Long, userId: Long)` (all `RuntimeException`). `ErrorResponse(code: String, message: String)`. `GlobalExceptionHandler` with no-arg constructor and public handler methods `handleNotFound`, `handleSoldOut`, `handleDuplicate`, `handleDataIntegrityViolation`, each returning `ResponseEntity<ErrorResponse>`.

- [ ] **Step 1: Write the failing handler test**

```kotlin
package com.coffee_coupon_api.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `쿠폰을 찾을 수 없으면 404를 반환한다`() {
        val response = handler.handleNotFound(CouponNotFoundException(1L))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("COUPON_NOT_FOUND", response.body?.code)
    }

    @Test
    fun `재고가 소진되면 409를 반환한다`() {
        val response = handler.handleSoldOut(CouponSoldOutException(1L))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("COUPON_SOLD_OUT", response.body?.code)
    }

    @Test
    fun `중복 발급이면 409를 반환한다`() {
        val response = handler.handleDuplicate(DuplicateIssueException(1L, 1L))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("DUPLICATE_ISSUE", response.body?.code)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest"`
Expected: FAIL — compile error, exception classes / `GlobalExceptionHandler` unresolved.

- [ ] **Step 3: Implement exception classes**

```kotlin
package com.coffee_coupon_api.exception

class CouponNotFoundException(couponId: Long) : RuntimeException("Coupon not found: $couponId")

class CouponSoldOutException(couponId: Long) : RuntimeException("Coupon sold out: $couponId")

class DuplicateIssueException(couponId: Long, userId: Long) :
    RuntimeException("Coupon $couponId already issued to user $userId")
```

- [ ] **Step 4: Implement `ErrorResponse`**

```kotlin
package com.coffee_coupon_api.dto

data class ErrorResponse(
    val code: String,
    val message: String,
)
```

- [ ] **Step 5: Implement `GlobalExceptionHandler`**

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
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest"`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt src/main/kotlin/com/coffee_coupon_api/dto/ErrorResponse.kt src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt
git commit -m "feat: add coupon exceptions and global exception handler"
```

---

### Task 4: `CouponService` (issue + getCoupon)

**Files:**
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`

**Interfaces:**
- Consumes: `CouponRepository` (Task 1: `findById(Long): Optional<Coupon>`, `save(Coupon): Coupon`), `CouponIssueRepository` (Task 2: `existsByCouponIdAndUserId(Long, Long): Boolean`, `save(CouponIssue): CouponIssue`), `CouponNotFoundException`, `CouponSoldOutException`, `DuplicateIssueException` (Task 3).
- Produces: `CouponService(couponRepository, couponIssueRepository)` with `fun issue(couponId: Long, userId: Long): CouponIssue` and `fun getCoupon(couponId: Long): Coupon`. Later controller task depends on these two exact signatures.

- [ ] **Step 1: Write the failing service tests**

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponRepository
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CouponServiceTest {

    private lateinit var couponRepository: CouponRepository
    private lateinit var couponIssueRepository: CouponIssueRepository
    private lateinit var couponService: CouponService

    @BeforeEach
    fun setUp() {
        couponRepository = mock(CouponRepository::class.java)
        couponIssueRepository = mock(CouponIssueRepository::class.java)
        couponService = CouponService(couponRepository, couponIssueRepository)
    }

    @Test
    fun `발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 0)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponRepository.save(coupon)).thenReturn(coupon)
        val savedIssue = CouponIssue(couponId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issue(1L, 100L)

        assertEquals(1, coupon.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issue(999L, 100L)
        }
    }

    @Test
    fun `이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 1)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 1)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `존재하는 쿠폰을 조회하면 쿠폰 정보를 반환한다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 3)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))

        val result = couponService.getCoupon(1L)

        assertEquals("아메리카노", result.name)
    }

    @Test
    fun `존재하지 않는 쿠폰을 조회하면 CouponNotFoundException이 발생한다`() {
        `when`(couponRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.getCoupon(999L)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest"`
Expected: FAIL — compile error, `CouponService` unresolved. No DB needed for this task (Mockito only).

- [ ] **Step 3: Implement `CouponService`**

```kotlin
package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponRepository
import org.springframework.stereotype.Service

@Service
class CouponService(
    private val couponRepository: CouponRepository,
    private val couponIssueRepository: CouponIssueRepository,
) {

    fun issue(couponId: Long, userId: Long): CouponIssue {
        val coupon = couponRepository.findById(couponId)
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

    fun getCoupon(couponId: Long): Coupon =
        couponRepository.findById(couponId)
            .orElseThrow { CouponNotFoundException(couponId) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt
git commit -m "feat: add CouponService with issue and getCoupon"
```

---

### Task 5: `CouponController` (issue + get endpoints) + integration tests

**Files:**
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/dto/CouponDtos.kt`
- Create: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt`

**Interfaces:**
- Consumes: `CouponService.issue(Long, Long): CouponIssue`, `CouponService.getCoupon(Long): Coupon` (Task 4); `CouponRepository`, `CouponIssueRepository` (Tasks 1–2, used directly by the test for setup); `GlobalExceptionHandler` (Task 3, wired automatically via `@RestControllerAdvice`).
- Produces: `POST /api/coupons/{couponId}/issue` and `GET /api/coupons/{couponId}` HTTP endpoints. This is the last task — nothing downstream depends on it.

- [ ] **Step 1: Write the failing controller tests**

```kotlin
package com.coffee_coupon_api.controller

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponRepository
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
    lateinit var couponRepository: CouponRepository

    @Autowired
    lateinit var couponIssueRepository: CouponIssueRepository

    private lateinit var coupon: Coupon

    @BeforeEach
    fun setUp() {
        coupon = couponRepository.save(Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 0))
    }

    @Test
    fun `쿠폰을 정상적으로 발급받는다`() {
        mockMvc.perform(
            post("/api/coupons/${coupon.id}/issue")
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
        couponIssueRepository.save(CouponIssue(couponId = coupon.id!!, userId = 1L))

        mockMvc.perform(
            post("/api/coupons/${coupon.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_ISSUE"))
    }

    @Test
    fun `재고가 소진되면 409를 반환한다`() {
        couponIssueRepository.save(CouponIssue(couponId = coupon.id!!, userId = 1L))
        coupon.issuedQuantity = 1
        couponRepository.save(coupon)

        mockMvc.perform(
            post("/api/coupons/${coupon.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 2L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"))
    }

    @Test
    fun `쿠폰 잔여 수량을 조회한다`() {
        mockMvc.perform(get("/api/coupons/${coupon.id}"))
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

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.coffee_coupon_api.controller.CouponControllerTest"`
Expected: FAIL — compile error, `CouponController` unresolved. Requires local MySQL running.

- [ ] **Step 3: Implement DTOs**

```kotlin
package com.coffee_coupon_api.dto

import java.time.LocalDateTime

data class CouponIssueRequest(
    val userId: Long,
)

data class CouponIssueResponse(
    val couponId: Long,
    val userId: Long,
    val issuedAt: LocalDateTime,
)

data class CouponResponse(
    val id: Long,
    val name: String,
    val totalQuantity: Int,
    val issuedQuantity: Int,
    val remainingQuantity: Int,
)
```

- [ ] **Step 4: Implement `CouponController`**

```kotlin
package com.coffee_coupon_api.controller

import com.coffee_coupon_api.dto.CouponIssueRequest
import com.coffee_coupon_api.dto.CouponIssueResponse
import com.coffee_coupon_api.dto.CouponResponse
import com.coffee_coupon_api.service.CouponService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/coupons")
class CouponController(
    private val couponService: CouponService,
) {

    @PostMapping("/{couponId}/issue")
    fun issue(
        @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        val issue = couponService.issue(couponId, request.userId)
        return CouponIssueResponse(
            couponId = issue.couponId,
            userId = issue.userId,
            issuedAt = issue.issuedAt,
        )
    }

    @GetMapping("/{couponId}")
    fun getCoupon(@PathVariable couponId: Long): CouponResponse {
        val coupon = couponService.getCoupon(couponId)
        return CouponResponse(
            id = coupon.id!!,
            name = coupon.name,
            totalQuantity = coupon.totalQuantity,
            issuedQuantity = coupon.issuedQuantity,
            remainingQuantity = coupon.totalQuantity - coupon.issuedQuantity,
        )
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.coffee_coupon_api.controller.CouponControllerTest"`
Expected: PASS (6 tests)

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: PASS (all tests across all tasks, plus the original `CoffeeCouponApiApplicationTests.contextLoads`)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/coffee_coupon_api/dto/CouponDtos.kt src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt
git commit -m "feat: add CouponController with issue and get endpoints"
```

---

## Out of Scope (deferred to later branches)

- Concurrency reproduction test (parallel requests proving over-issuance) — next feature after this one, per the spec's stated roadmap.
- Pessimistic / optimistic / distributed lock implementations — separate `feature/*` branches per the Git Flow strategy in the README.
- Authentication — `userId` is passed unauthenticated in the request body, as decided in the spec.
