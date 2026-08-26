# 쿠폰 발급 분산 락(Redis/Redisson) 설계

- 날짜: 2026-08-26
- 브랜치: `feature/coupon-issue-distributed-lock`
- 목적: 비관적 락/낙관적 락은 둘 다 "락 상태가 DB(공유 자원) 안에 있다"는 이유로 서버 대수와 무관하게 정확성을 보장하지만, 그 대가로 락 대기가 DB 커넥션 풀을 소모한다(비관적 락은 대기 자체가 커넥션 점유, 낙관적 락은 오늘 발견한 `REQUIRES_NEW` 커넥션 풀 데드락 사례로 실증됨). 분산 락은 이 "대기"를 DB와 완전히 분리된 자원(Redis)으로 옮겨서, DB 커넥션 풀을 소모하지 않고 동시성을 제어하는 네 번째 전략이다.

## 범위

- `docker-compose.yml`에 Redis 단일 인스턴스를 추가한다.
- Redisson(`redisson-spring-boot-starter`)을 도입하고, `CouponService.issueDistributed` + `POST /api/coupons/{campaignId}/issue-distributed` 엔드포인트를 새로 만든다.
- 락 획득 실패(타임아웃) 시 전용 예외로 처리한다.
- Redlock(다중 Redis 마스터)은 범위 밖 — 이 프로젝트는 학습/비교 목적이고 MySQL도 이미 단일 인스턴스이므로, 락 저장소도 단일 인스턴스로 맞춘다.
- 재고를 Redis `INCR`/`DECR`로 직접 관리하는 방식도 범위 밖 — 이슈 #14는 "MySQL을 건드리는 코드 블록에 누가 들어갈 차례인지"를 Redis로 조율하는 것이지, 재고 데이터 자체를 Redis로 옮기는 게 아니다.
- 부하테스트 하네스(`load-test/coupon-issue-scale.js`)는 이미 `STRATEGY=distributed`를 지원하도록 만들어져 있어, 엔드포인트만 추가되면 별도 작업 없이 바로 4개 전략 비교가 가능하다 — 이번 스펙 범위엔 안 넣는다.

## 1. 인프라 — Redis (Docker)

MySQL은 로컬에 직접 설치돼 있지만, Redis는 이 기회에 Docker로 띄운다(`docker-compose.yml` 신규 작성, MySQL 쪽은 건드리지 않는다 — 범위 밖).

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

`application-local.yaml`에 Redisson 클라이언트가 연결할 주소(`localhost:6379`)를 추가한다.

## 2. 라이브러리 선택 — Redisson vs 직접 SETNX 구현

| | 직접 SETNX 구현 | Redisson |
|---|---|---|
| 안전한 락 해제(남의 락을 실수로 못 지우게) | 직접 구현(값 비교 후 삭제하는 Lua 스크립트 필요) | 이미 구현됨 |
| TTL 자동 갱신(watchdog) | 직접 구현(작업 중인데 TTL이 끝나갈 때 갱신하는 별도 로직 필요) | 이미 구현됨(`tryLock`에 leaseTime을 안 주면 자동 활성화) |
| 재진입/공정 락 등 확장 | 직접 구현 | `RFairLock`/`RReadWriteLock` 등 기본 제공 |
| 이 프로젝트 목적과의 부합 | Redis 프로토콜 자체를 재구현하는 것 | 락 "전략" 비교에 집중 가능(비관적/낙관적 락도 프레임워크 제공 기능(`FOR UPDATE`/`@Version`)을 썼지, 락 프로토콜을 직접 구현하지 않았다) |

**결정: Redisson.** 안전한 해제와 watchdog은 정확히 오늘 학습한 Redlock/펜싱 토큰 논쟁에서 나온 "직접 구현하면 놓치기 쉬운 지점"이라, 검증된 라이브러리를 쓰는 게 맞다.

버전은 구현 시점에 Maven Central에서 이 프로젝트의 Spring Boot 4.1.0 / Spring Framework 7.0.8과 호환되는 최신 `redisson-spring-boot-starter` 버전을 확인해서 고정한다(현재 스펙 작성 시점 기준 정확한 버전 번호를 단정하지 않는다 — Spring Boot 4.x는 비교적 최신이라 호환성을 실제로 확인해야 한다).

## 3. 서비스 로직 — `issueDistributed`

오늘 커넥션 풀 데드락을 고치며 확인한 원칙("커넥션은 필요한 순간에만 짧게 쥔다")을 그대로 적용한다. 락 대기는 DB와 무관한 Redis에서 일어나야 하므로, `issueDistributed`엔 `@Transactional`을 붙이지 않는다.

```kotlin
fun issueDistributed(couponCampaignId: Long, userId: Long): CouponIssue {
    val lock = redissonClient.getLock("coupon-lock:$couponCampaignId")
    val acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
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

- `tryLock(waitTime, TimeUnit)`을 `leaseTime` 없이 호출하면 Redisson의 watchdog이 자동으로 붙는다 — 스레드가 살아있는 동안 TTL을 계속 갱신해주므로, 처리 시간이 길어져도 락이 중간에 새지 않는다.
- `attemptIssue()`(기존 `CouponIssueAttempter`의 `@Transactional(REQUIRES_NEW)` 메서드)를 **그대로 재사용**한다 — 새 메서드를 만들 필요가 없다. Redis 락이 "동시에 하나만 들어온다"를 이미 보장하므로, `attemptIssue` 안의 `@Version` 체크는 정상 경로에서는 거의 항상 통과하지만, **의도적으로 제거하지 않고 보험으로 남겨둔다**(락 TTL이 작업 도중 만료되는 등의 엣지 케이스 대비 — 오늘 논의했던 펜싱 토큰과 같은 방향의 방어).
- `LOCK_WAIT_SECONDS`는 3초로 시작한다(비관적 락의 DB 락 대기와 달리, 이 대기는 DB 커넥션을 전혀 안 쓰므로 좀 더 여유롭게 잡아도 부담이 적다). 정확한 값은 부하테스트로 조정 가능하도록 상수로 분리한다.

## 4. 락 획득 실패 처리 — 새 예외

낙관적 락의 "재시도 소진"과 분산 락의 "락 획득 타임아웃"은 원인이 다르므로 별도 예외로 분리한다(이 프로젝트가 지금까지 지켜온 "예외 하나 = 실패 사유 하나" 컨벤션 유지, 그리고 나중에 부하테스트 결과에서 전략별 실패 사유를 구분해 보기 위함).

```kotlin
class CouponIssueLockTimeoutException(couponId: Long) :
    RuntimeException("분산 락 획득 실패: campaign=$couponId")
```

```kotlin
@ExceptionHandler(CouponIssueLockTimeoutException::class)
fun handleLockTimeout(ex: CouponIssueLockTimeoutException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse("COUPON_ISSUE_LOCK_TIMEOUT", ex.message ?: "Lock acquisition timeout"))
```

HTTP 상태 코드는 낙관적 락의 재시도 소진과 동일하게 409로 맞춘다(클라이언트 입장에서는 둘 다 "동시성 경합으로 실패, 재시도 가능"이라는 동일한 대응이 적절하다) — 다만 에러 코드 문자열은 `COUPON_ISSUE_LOCK_TIMEOUT`으로 구분해서, 원인은 명확히 남긴다.

## 5. 컨트롤러

`POST /api/coupons/{couponId}/issue-distributed` — 기존 세 엔드포인트와 동일한 패턴(Swagger `@Operation`/`@ApiResponses`에 409(`COUPON_ISSUE_LOCK_TIMEOUT`) 케이스 추가).

## 6. 에러 처리 / 알려진 한계

- **Redis 자체가 다운되면**: `RedissonClient` 호출이 예외를 던진다(연결 실패). 이번 스펙에서는 별도 폴백(예: MySQL 명명된 락으로 전환)을 만들지 않고, 그대로 5xx로 노출되는 걸 알려진 한계로 기록한다 — 이 프로젝트의 목적(락 전략 비교)에서 벗어나는 고가용성 주제다.
- **락 대기 3초 동안 스레드가 블로킹됨**: Tomcat 스레드 풀이 소모되긴 하지만, DB 커넥션 풀과는 별개 자원이라 오늘 겪은 데드락과는 다른 종류의 문제다. 다만 대량 동시 요청 시 Tomcat 스레드 풀 자체가 고갈될 가능성은 남아있다 — 알려진 한계로 기록.
- **`isHeldByCurrentThread` 체크**: `tryLock`이 타임아웃으로 실패했는데도 `unlock()`을 호출하면 `IllegalMonitorStateException`이 나므로, 반드시 락을 실제로 잡았을 때만 해제한다.

## 7. 테스트

- `CouponServiceTest`: `RedissonClient`/`RLock`을 목으로 구성해 정상 발급, 락 획득 실패(`CouponIssueLockTimeoutException`) 케이스를 단위 테스트로 추가.
- `CouponServiceConcurrencyTest`: 기존 `runConcurrently` 패턴을 재사용해, 재고 1개 캠페인에 N명 동시 요청 → 1명만 성공을 검증(비관적 락 테스트와 동일한 기대값, 다만 이번엔 Redis가 필요하다 — 로컬 Redis(`docker compose up redis`)가 떠 있어야 통과하는 테스트라는 걸 README/테스트 코드 주석에 명시한다).
- 부하테스트: `STRATEGY=distributed`로 기존 하네스를 그대로 실행해 과발급 없음 + 커넥션 풀 데드락 없음(오늘 optimistic에서 겪은 것과 같은 패턴이 재발하지 않는지)을 확인한다.

## 결정된 사항 요약

- Redis는 Docker로 단일 인스턴스만 띄움(Redlock 미사용)
- 락 라이브러리: Redisson(`redisson-spring-boot-starter`) — 직접 SETNX 구현 안 함
- `issueDistributed`엔 `@Transactional` 없음, 기존 `attemptIssue()`(`REQUIRES_NEW`) 재사용
- `@Version`은 보험으로 그대로 유지(제거하지 않음)
- 락 획득 실패는 새 예외 `CouponIssueLockTimeoutException` → 409 `COUPON_ISSUE_LOCK_TIMEOUT`(낙관적 락의 `COUPON_ISSUE_CONFLICT`와 분리)
- 락 대기 시간은 상수(`LOCK_WAIT_SECONDS = 3`)로 분리, 필요시 조정
