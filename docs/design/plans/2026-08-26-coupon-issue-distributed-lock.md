# 쿠폰 발급 분산 락(Redis/Redisson) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/coupons/{couponId}/issue-distributed` 엔드포인트를 추가해, Redisson 기반 분산 락으로 쿠폰 발급 동시성을 제어한다.

**Architecture:** `CouponService.issueDistributed`가 `RedissonClient`로 `coupon-lock:{campaignId}` 키에 락을 잡고, 그 안에서 기존 `CouponIssueAttempter.attemptIssue()`(REQUIRES_NEW 트랜잭션)를 그대로 재사용한다. 락 대기는 DB 커넥션과 무관한 Redis에서 일어나므로 `issueDistributed` 자체엔 `@Transactional`을 붙이지 않는다.

**Tech Stack:** Kotlin, Spring Boot 4.1.0, Redisson(`redisson-spring-boot-starter` 4.1.0), Docker(Redis 7), MySQL, JUnit5, Mockito.

**Spec:** `docs/superpowers/specs/2026-08-26-coupon-issue-distributed-lock-design.md`

## Global Constraints

- Redis는 Docker로 단일 인스턴스만 띄운다(Redlock 미사용).
- 락 라이브러리는 Redisson만 사용한다(직접 SETNX 구현 금지).
- `issueDistributed`엔 `@Transactional`을 붙이지 않는다. 기존 `CouponIssueAttempter.attemptIssue()`(변경 없이 그대로)를 재사용한다.
- `@Version`(낙관적 락)은 제거하지 않고 보험으로 유지한다.
- 락 획득 실패는 새 예외 `CouponIssueLockTimeoutException`으로 처리하고, `COUPON_ISSUE_CONFLICT`(낙관적 락)와 다른 에러 코드 `COUPON_ISSUE_LOCK_TIMEOUT`을 쓴다. HTTP 상태는 둘 다 409.
- 락 대기 시간은 상수/생성자 파라미터로 분리해 테스트에서 오버라이드 가능하게 한다(`LOCK_WAIT_SECONDS = 3`).
- **이 플랜 완료 후, `@SpringBootTest`가 붙은 모든 테스트(이미 존재하는 `CouponControllerTest`, `CouponServiceConcurrencyTest` 등 포함)는 로컬 Redis가 떠 있어야 통과한다** — `RedissonClient`가 Spring 컨텍스트의 빈이 되므로, 컨텍스트를 로드하는 모든 테스트가 영향을 받는다. `./gradlew test`를 돌리기 전에 `docker compose up -d redis`가 필요하다.

---

### Task 1: Redis 인프라(Docker) + Redisson 의존성 추가

**Files:**
- Create: `docker-compose.yml` (레포 루트, `coffee-coupon-api/`와 같은 레벨)
- Modify: `coffee-coupon-api/build.gradle.kts`
- Modify: `coffee-coupon-api/src/main/resources/application-local.yaml.example`
- Modify: `coffee-coupon-api/src/main/resources/application-local.yaml` (gitignore 대상이라 커밋되지 않지만, 로컬 실행을 위해 이 저장소에 이미 존재하는 파일을 함께 수정한다)
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/RedissonConnectionTest.kt`

**Interfaces:**
- Produces: `RedissonClient` Spring 빈(Redisson 스타터의 오토컨피규레이션이 자동 등록) — 이후 모든 태스크가 이 빈을 주입받아 쓴다.

- [ ] **Step 1: docker-compose.yml 작성**

레포 루트(`coffee-coupon-api/`와 같은 위치, 즉 `docker-compose.yml`의 경로는 `/docker-compose.yml`)에 다음 내용으로 새로 만든다:

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

- [ ] **Step 2: Redis 컨테이너 기동 확인**

Run: `docker compose up -d redis && docker compose ps`
Expected: `redis` 서비스가 `running`(또는 `Up`) 상태로 나옴.

- [ ] **Step 3: 실패하는 테스트 작성 (RedissonClient 연결 확인)**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/RedissonConnectionTest.kt`를 새로 만든다:

```kotlin
package com.coffee_coupon_api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class RedissonConnectionTest {

    @Autowired
    lateinit var redissonClient: RedissonClient

    @Test
    fun `RedissonClient로 락을 잡고 풀 수 있다`() {
        val lock = redissonClient.getLock("redisson-connection-test-lock")
        val acquired = lock.tryLock()
        assertTrue(acquired, "락을 잡지 못했다 — Redis 연결을 확인하세요")
        lock.unlock()
    }
}
```

- [ ] **Step 4: 테스트 실행해서 실패 확인 (컴파일 실패)**

Run: `cd coffee-coupon-api && ./gradlew compileTestKotlin`
Expected: FAIL — `org.redisson.api.RedissonClient`를 찾을 수 없다는 컴파일 에러(아직 의존성을 안 넣었으므로).

- [ ] **Step 5: Redisson 의존성 추가**

`coffee-coupon-api/build.gradle.kts`의 `dependencies` 블록에서, `runtimeOnly("com.mysql:mysql-connector-j")` 바로 다음 줄에 추가:

```kotlin
	runtimeOnly("com.mysql:mysql-connector-j")
	implementation("org.redisson:redisson-spring-boot-starter:4.1.0")
```

- [ ] **Step 6: 로컬 Redis 연결 설정 추가**

`coffee-coupon-api/src/main/resources/application-local.yaml.example`의 `spring:` 블록 밑, `datasource:` 앞에 추가:

```yaml
spring:
  application:
    name: coffee-coupon-api
  data:
    redis:
      host: localhost
      port: 6379
  datasource:
    url: jdbc:mysql://localhost:3306/coffee_coupon?createDatabaseIfNotExist=true
    username: root
    password: your_password_here
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      # 이 브랜치는 테이블/컬럼 rename을 포함하므로 기존 로컬 DB에는 update가 깨끗하게 적용되지 않는다.
      # 로컬 DB를 새로 만들거나 최초 1회 ddl-auto: create로 기동할 것.
      ddl-auto: update
    show-sql: true
```

같은 내용을 실제 로컬 설정 파일 `coffee-coupon-api/src/main/resources/application-local.yaml`에도 동일하게 추가한다(이 파일은 기존 `datasource`/`jpa` 설정을 그대로 두고, `data: redis: host/port` 블록만 `spring:` 밑에 추가).

- [ ] **Step 7: 테스트 재실행해서 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.RedissonConnectionTest" --rerun`
Expected: PASS — 1 test, 0 failures. (실패하면 `docker compose ps`로 Redis 컨테이너가 떠 있는지, `application-local.yaml`의 `data.redis.host/port`가 맞는지 확인. Spring Boot 4.0+에서 Redisson 오토컨피규레이션 충돌이 나면 [Redisson 공식 Spring 연동 문서](https://redisson.pro/docs/integration-with-spring/)의 `RedissonAutoConfigurationV4` 관련 안내를 참고.)

- [ ] **Step 8: 커밋**

```bash
git add docker-compose.yml coffee-coupon-api/build.gradle.kts coffee-coupon-api/src/main/resources/application-local.yaml.example coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/RedissonConnectionTest.kt
git commit -m "feat: Redis(Docker) + Redisson 의존성 추가"
```

(`application-local.yaml`은 gitignore 대상이라 커밋 대상에서 제외한다.)

---

### Task 2: `CouponIssueLockTimeoutException` + 예외 처리

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt`
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt`
- Test: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`

**Interfaces:**
- Produces: `CouponIssueLockTimeoutException(couponId: Long)`, `GlobalExceptionHandler.handleLockTimeout(ex: CouponIssueLockTimeoutException): ResponseEntity<ErrorResponse>` — Task 3의 `CouponService.issueDistributed`가 이 예외를 던진다.

- [ ] **Step 1: 실패하는 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt`의 마지막 `@Test` 함수(`낙관적 락 재시도가 소진되면 409를 반환한다`) 바로 다음에 추가:

```kotlin
    @Test
    fun `분산 락 획득에 실패하면 409를 반환한다`() {
        val response = handler.handleLockTimeout(CouponIssueLockTimeoutException(1L))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("COUPON_ISSUE_LOCK_TIMEOUT", response.body?.code)
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew compileTestKotlin`
Expected: FAIL — `CouponIssueLockTimeoutException`과 `handleLockTimeout`을 찾을 수 없다는 컴파일 에러.

- [ ] **Step 3: 예외 클래스 추가**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt`의 마지막 줄(`CouponIssueConflictException` 정의) 다음에 추가:

```kotlin

class CouponIssueLockTimeoutException(couponId: Long) :
    RuntimeException("분산 락 획득 실패: campaign=$couponId")
```

- [ ] **Step 4: 예외 핸들러 추가**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt`의 마지막 `@ExceptionHandler`(`handleIssueConflict`) 다음, 클래스를 닫는 `}` 앞에 추가:

```kotlin

    @ExceptionHandler(CouponIssueLockTimeoutException::class)
    fun handleLockTimeout(ex: CouponIssueLockTimeoutException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("COUPON_ISSUE_LOCK_TIMEOUT", ex.message ?: "Lock acquisition timeout"))
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.exception.GlobalExceptionHandlerTest" --rerun`
Expected: PASS — 7 tests, 0 failures.

- [ ] **Step 6: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/CouponExceptions.kt coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandler.kt coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/exception/GlobalExceptionHandlerTest.kt
git commit -m "feat: 분산 락 타임아웃 예외(CouponIssueLockTimeoutException) 추가"
```

---

### Task 3: `CouponService.issueDistributed`

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt`

**Interfaces:**
- Consumes: `CouponIssueAttempter.attemptIssue(couponCampaignId: Long, userId: Long): CouponIssue` (기존, 변경 없음), `CouponIssueLockTimeoutException(couponId: Long)` (Task 2에서 생성).
- Produces: `CouponService.issueDistributed(couponCampaignId: Long, userId: Long): CouponIssue` — Task 4의 컨트롤러가 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성 — 정상 발급**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt` 맨 위 import 블록에 추가:

```kotlin
import com.coffee_coupon_api.exception.CouponIssueLockTimeoutException
import java.util.concurrent.TimeUnit
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
```

클래스 필드 선언부(`private lateinit var couponIssueAttempter: CouponIssueAttempter` 다음 줄)에 추가:

```kotlin
    private lateinit var redissonClient: RedissonClient
    private lateinit var lock: RLock
```

`setUp()` 안, `couponIssueAttempter = CouponIssueAttempter(...)` 다음 줄에 추가:

```kotlin
        redissonClient = mock(RedissonClient::class.java)
        lock = mock(RLock::class.java)
        `when`(redissonClient.getLock(any(String::class.java))).thenReturn(lock)
```

`setUp()`의 `couponService = CouponService(...)` 줄을 찾아서, 끝에 `redissonClient = redissonClient`를 추가:

```kotlin
        couponService = CouponService(
            couponCampaignRepository,
            couponTemplateRepository,
            couponIssueAttempter = couponIssueAttempter,
            redissonClient = redissonClient,
        )
```

파일 맨 끝(마지막 `}` 앞)에 새 테스트 두 개를 추가:

```kotlin

    @Test
    fun `issueDistributed - 락 획득에 성공하면 정상적으로 발급된다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(lock.tryLock(3L, TimeUnit.SECONDS)).thenReturn(true)
        `when`(lock.isHeldByCurrentThread).thenReturn(true)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issueDistributed(1L, 100L)

        assertEquals(1, campaign.issuedQuantity)
        assertEquals(100L, result.userId)
        verify(lock).unlock()
    }

    @Test
    fun `issueDistributed - 락 획득에 실패하면 CouponIssueLockTimeoutException이 발생한다`() {
        `when`(lock.tryLock(3L, TimeUnit.SECONDS)).thenReturn(false)

        assertThrows(CouponIssueLockTimeoutException::class.java) {
            couponService.issueDistributed(1L, 100L)
        }

        verify(lock, Mockito.never()).unlock()
    }
```

`import org.mockito.Mockito`도 import 블록에 추가한다(`import org.mockito.Mockito.verify` 다음 줄).

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew compileTestKotlin`
Expected: FAIL — `CouponService`에 `issueDistributed` 메서드가 없고 `redissonClient` 생성자 파라미터도 없다는 컴파일 에러.

- [ ] **Step 3: `CouponService`에 `issueDistributed` 구현**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt`의 import 블록 맨 위(`import com.coffee_coupon_api.domain.CouponCampaign` 앞)에 추가:

```kotlin
import com.coffee_coupon_api.exception.CouponIssueLockTimeoutException
import java.util.concurrent.TimeUnit
import org.redisson.api.RedissonClient
```

`private const val OPTIMISTIC_RETRY_DELAY_MS = 20L` 다음 줄에 추가:

```kotlin
private const val LOCK_WAIT_SECONDS = 3L
```

생성자를 아래처럼 바꾼다(기존 파라미터 순서 뒤에 두 개를 추가):

```kotlin
@Service
class CouponService(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val couponIssueAttempter: CouponIssueAttempter,
    private val optimisticMaxAttempts: Int = 3,
    private val redissonClient: RedissonClient,
    private val lockWaitSeconds: Long = LOCK_WAIT_SECONDS,
) {
```

`fun issueOptimistic(...)` 메서드가 끝나는 `}` 다음 줄(즉 `private fun incrementIssuedQuantityRaw` 앞)에 새 메서드를 추가:

```kotlin

    fun issueDistributed(couponCampaignId: Long, userId: Long): CouponIssue {
        val lock = redissonClient.getLock("coupon-lock:$couponCampaignId")
        val acquired = lock.tryLock(lockWaitSeconds, TimeUnit.SECONDS)
        if (!acquired) {
            throw CouponIssueLockTimeoutException(couponCampaignId)
        }
        try {
            return couponIssueAttempter.attemptIssue(couponCampaignId, userId)
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }
```

- [ ] **Step 4: 기존 `CouponService(...)` 생성 지점 5곳 전부 수정**

`redissonClient`가 기본값 없는 필수 파라미터라, `CouponServiceTest.kt` 안의 기존 생성 지점(Step 1에서 고친 `setUp()` 제외) 전부에 `redissonClient = redissonClient`를 추가해야 컴파일된다. 아래 4개 테스트 함수에서 각각 `CouponService(...)` 호출을 찾아 끝에 `redissonClient = redissonClient,`를 추가한다:

`issuePessimistic - 오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`:
```kotlin
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, fixedClock, couponIssueAttempter, redissonClient = redissonClient)
```

`issuePessimistic - 오픈 시각 정각에 발급 요청하면 정상적으로 발급된다`:
```kotlin
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, fixedClock, couponIssueAttempter, redissonClient = redissonClient)
```

`issueNoLock - 오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`:
```kotlin
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, fixedClock, couponIssueAttempter, redissonClient = redissonClient)
```

`issueOptimistic - 오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`:
```kotlin
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueAttempter = attempterWithFixedClock, redissonClient = redissonClient)
```

`issueOptimistic - 재시도를 다 써도 계속 충돌하면 CouponIssueConflictException이 발생한다`:
```kotlin
        val serviceWithOneAttempt = CouponService(
            couponCampaignRepository,
            couponTemplateRepository,
            couponIssueAttempter = couponIssueAttempter,
            optimisticMaxAttempts = 1,
            redissonClient = redissonClient,
        )
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceTest" --rerun`
Expected: PASS — 22 tests, 0 failures.

- [ ] **Step 6: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/service/CouponService.kt coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceTest.kt
git commit -m "feat: CouponService.issueDistributed 구현 (Redisson 분산 락)"
```

---

### Task 4: 컨트롤러 엔드포인트 `POST /api/coupons/{couponId}/issue-distributed`

**Files:**
- Modify: `coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt`
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt`

**Interfaces:**
- Consumes: `CouponService.issueDistributed(couponCampaignId: Long, userId: Long): CouponIssue` (Task 3에서 생성).

- [ ] **Step 1: 실패하는 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt`의 `issue-optimistic - 쿠폰을 정상적으로 발급받는다` 테스트 함수(닫는 `}`) 바로 다음에 추가:

```kotlin

    @Test
    fun `issue-distributed - 쿠폰을 정상적으로 발급받는다`() {
        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue-distributed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(1))
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.controller.CouponControllerTest" --rerun`
Expected: FAIL — `/api/coupons/{id}/issue-distributed` 엔드포인트가 없어서 404.

- [ ] **Step 3: 컨트롤러에 엔드포인트 추가**

`coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt`의 `issueOptimistic` 함수가 끝나는 `}` 다음, `getCoupon`의 `@Operation` 앞에 추가:

```kotlin

    @Operation(
        summary = "쿠폰 발급 (분산 락)",
        description = "지정한 쿠폰을 사용자에게 발급한다. Redisson 기반 분산 락(`coupon-lock:{campaignId}`)으로 " +
            "여러 서버 인스턴스 사이의 동시 요청을 조율한다. 락 획득에 실패하면(대기 시간 초과) 409를 반환한다.",
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
                "또는 분산 락 획득 타임아웃(COUPON_ISSUE_LOCK_TIMEOUT)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{couponId}/issue-distributed")
    fun issueDistributed(
        @Parameter(description = "발급할 쿠폰 ID") @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        return couponService.issueDistributed(couponId, request.userId).toResponse()
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.controller.CouponControllerTest" --rerun`
Expected: PASS — 10 tests, 0 failures. (Redis가 안 떠 있으면 컨텍스트 로딩 자체가 실패한다 — `docker compose up -d redis` 먼저 확인.)

- [ ] **Step 5: 커밋**

```bash
git add coffee-coupon-api/src/main/kotlin/com/coffee_coupon_api/controller/CouponController.kt coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/controller/CouponControllerTest.kt
git commit -m "feat: POST /api/coupons/{id}/issue-distributed 엔드포인트 추가"
```

---

### Task 5: 실제 동시성 검증 — `CouponServiceConcurrencyTest`

**Files:**
- Modify: `coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`

**Interfaces:**
- Consumes: `CouponService.issueDistributed(couponCampaignId: Long, userId: Long): CouponIssue` (Task 3), 기존 `seedOpenCampaign`/`runConcurrently` 헬퍼(파일 내 기존 private 함수, 변경 없음).

- [ ] **Step 1: 새 동시성 테스트 작성**

`coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt`의 `락이 없으면 재고 1개짜리...` 테스트 함수가 끝나는 `}` 다음, `private fun seedOpenCampaign` 앞에 추가:

```kotlin

    @Test
    fun `분산 락을 걸면 재고 1개짜리 쿠폰에 N명이 동시에 요청해도 1명만 성공한다`() {
        val campaign = seedOpenCampaign(totalQuantity = 1)
        val threadCount = 30
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runConcurrently(threadCount) { i ->
            try {
                couponService.issueDistributed(campaign.id!!, userId = i.toLong())
                successCount.incrementAndGet()
            } catch (e: Exception) {
                failCount.incrementAndGet()
            }
        }

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[분산 락] totalQuantity=1, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        assertEquals(1, successCount.get())
        assertEquals(threadCount - 1, failCount.get())
        assertEquals(1, finalCampaign.issuedQuantity)
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceConcurrencyTest" --rerun`
Expected: FAIL — 만약 Task 1~4가 정상 완료된 상태라면 이 테스트는 이미 컴파일되고 통과할 가능성이 높다. 만약 실패한다면(예: `successCount != 1`), Task 3의 락/언락 로직 또는 `LOCK_WAIT_SECONDS`가 30명 동시 요청을 감당하기에 너무 짧은지 확인한다.

- [ ] **Step 3: 필요시 수정 후 재실행**

만약 Step 2에서 타임아웃으로 인한 실패(성공 1명보다 적음)가 발생하면, `CouponServiceConcurrencyTest`는 실제 `CouponService` 빈(기본 `lockWaitSeconds = 3`)을 그대로 쓰므로, 30개 스레드가 순차적으로 락을 기다리다 3초를 넘길 수 있다. 이 경우 `runConcurrently`의 `doneLatch.await(10, TimeUnit.SECONDS)` 대기 시간을 `30`으로 늘린다(파일 내 `runConcurrently` 함수의 `doneLatch.await(10, TimeUnit.SECONDS)` 줄을 `doneLatch.await(30, TimeUnit.SECONDS)`로 수정) — 이건 락 자체의 문제가 아니라 테스트 타임아웃 여유의 문제다.

Run: `cd coffee-coupon-api && ./gradlew test --tests "com.coffee_coupon_api.service.CouponServiceConcurrencyTest" --rerun`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 4: 커밋**

```bash
git add coffee-coupon-api/src/test/kotlin/com/coffee_coupon_api/service/CouponServiceConcurrencyTest.kt
git commit -m "test: 분산 락 동시성 재현 테스트 추가"
```

---

### Task 6: 부하테스트로 최종 검증

**Files:** (코드 변경 없음, 검증만)

**Interfaces:**
- Consumes: `coffee-coupon-api/load-test/coupon-issue-scale.js`(기존, `STRATEGY=distributed` 이미 지원), `coffee-coupon-api/load-test/seed.sql`(기존), `coffee-coupon-api/load-test/verify.sh`(기존) — 전부 변경 없이 그대로 사용.

- [ ] **Step 1: Redis + 앱 기동**

```bash
docker compose up -d redis
cd coffee-coupon-api
./gradlew bootRun
```

(별도 터미널에서 계속 띄워둔다.)

- [ ] **Step 2: 캠페인 시딩**

```bash
MYSQL_PWD='<로컬 비밀번호>' mysql -h 127.0.0.1 -u root -D coffee_coupon < coffee-coupon-api/load-test/seed.sql
```

Expected: `campaign_id` 값이 출력됨(다음 스텝에서 사용).

- [ ] **Step 3: k6 부하 실행**

```bash
k6 run -e CAMPAIGN_ID=<위에서 나온 값> -e STRATEGY=distributed coffee-coupon-api/load-test/coupon-issue-scale.js
```

Expected: `checks_succeeded: 100.00%`, `dropped_iterations`가 0에 가까움(오늘 optimistic에서 겪은 커넥션 풀 데드락과 같은 패턴 — `http_req_duration`이 30초 근처로 튀는 대량 타임아웃 — 이 없어야 한다).

- [ ] **Step 4: 정합성 검증**

```bash
MYSQL_PWD='<로컬 비밀번호>' coffee-coupon-api/load-test/verify.sh <campaign_id>
```

Expected: `PASS: 과발급 없음`.

- [ ] **Step 5: 앱 정리**

```bash
pkill -f "CoffeeCouponApiApplicationKt"
```

- [ ] **Step 6: `load-test/README.md`의 비교표에 결과 기록**

`coffee-coupon-api/load-test/README.md`의 "전략 하나를 검증하는 순서" 아래 비교표에서 "분산 락" 행에 이번 실행의 성공/실패/과발급 여부/비고를 채워 넣는다(이 표는 이미 파일에 존재 — 새로 만들 필요 없음, 셀 채우기만).
