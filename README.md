## 프로젝트 소개

선착순 커피 쿠폰 발급 API에서 락 없음, 비관적 락, 낙관적 락 세 가지 동시성 전략을 동일 조건에서 비교한 프로젝트입니다.
목표는 "어떤 락이 더 좋다"를 가리는 게 아니라, 트래픽 규모와 서비스가 허용할 수 있는 지연에 따라 적합한 전략이 달라진다는 걸 직접 검증하는 것입니다.

## 트래픽 가정과 검증 방법

- 30만 명 대상, 시도율 20%, 오픈 직후 10초 집중 트래픽을 가정해 peak QPS를 추정
- 로컬 환경 한계에 맞춰 500 QPS 부하로 재현 (k6, seed.sql, verify.sh로 테스트 하네스 구성)

## 실험 결과

| 전략 | 평균 응답 | 발급 수량 |
| --- | --- | --- |
| 락 없음 | - | 2,008건 성공 (재고 2,000장 대비 8장 과발급) |
| 비관적 락 | 1.44초 | 정확히 2,000장 |
| 낙관적 락 | 0.09초 | 정확히 2,000장 (단, 요청 집중 시 재시도 반복으로 지연·DB 부하 증가) |

## 발견한 문제와 해결

- 락 없이 구현했을 때 카운터는 정상으로 보였지만 실제 발급 행이 재고를 초과. 화면 결과만으로는 정합성을 보장할 수 없다는 걸 확인
- 재시도 트랜잭션이 이전 트랜잭션과 얽혀 유령 행이 남고 재고가 초과 소모되는 문제 발견 → `REQUIRES_NEW`로 격리
- 이 과정에서 생긴 커넥션 풀 고갈·타임아웃을 재시도 루프 구조에서 원인을 찾아 해결
- AI가 제안한 `refresh()` 방식은 MySQL REPEATABLE READ 특성상 통하지 않는다는 걸 직접 실험으로 확인한 뒤 기각

## 결론

하나의 실험 결과만으로 더 나은 기술을 단정할 수 없습니다. 기술 자체보다 사용자 수, 요청이 몰리는 시점, 서비스가 허용할 수 있는 지연을 기준으로 전략을 선택해야 한다는 걸 확인했습니다.

## 기술 블로그

- [synchronized를 썼는데 왜 깨질까? 실험 6개로 뜯어본 JVM 락](https://0sunset0.tistory.com/17)

## 기술 스택

- ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
- ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white)
- ![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-6DB33F?style=flat-square&logo=spring&logoColor=white)
- ![Spring MVC](https://img.shields.io/badge/Spring%20MVC-6DB33F?style=flat-square&logo=spring&logoColor=white)
- ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white)
- ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)
- ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=flat-square&logo=junit5&logoColor=white)

## 실행 방법

모든 명령은 저장소 루트에서 실행합니다.

### 사전 준비

- JDK 17
- Docker (MySQL 실행용)

앱과 테스트 모두 MySQL이 필요합니다. 기본 접속 정보(`localhost:3306`, `root` / `coffee`, DB `coffee_coupon`)는 `docker-compose.yml`과 맞춰져 있어 별도 설정 없이 동작합니다.

> 이 비밀번호는 로컬 개발 전용 공개 값입니다. compose MySQL은 `127.0.0.1`에만 열려 같은 네트워크의 다른 기기에서는 접속할 수 없습니다. 운영 환경에서는 반드시 환경 변수로 실제 접속 정보를 주입하세요.

직접 설치한 MySQL을 쓰려면 환경 변수로 접속 정보를 지정하세요.

```bash
export DB_URL='jdbc:mysql://localhost:3306/coffee_coupon?createDatabaseIfNotExist=true'
export DB_PASSWORD='<내 MySQL 비밀번호>'
```

매번 export하기 번거로우면 `coffee-coupon-api/src/main/resources/application-local.yaml`(gitignore 대상)에 `spring.datasource.*` 값을 적어두면 기본값을 덮어씁니다.

### 1. MySQL 실행

```bash
docker compose up -d --wait
```

로컬에 이미 MySQL이 떠 있어 3306이 사용 중이면 포트를 바꿔 띄우고, 앱에도 같은 포트를 알려주세요.

```bash
MYSQL_PORT=3307 docker compose up -d --wait
export DB_URL='jdbc:mysql://localhost:3307/coffee_coupon?createDatabaseIfNotExist=true'
```

### 2. 앱 실행

```bash
cd coffee-coupon-api && ./gradlew bootRun
```

첫 기동 시 테이블이 자동 생성됩니다(`ddl-auto: update`).

### 3. 헬스체크

```bash
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

### 4. 테스트 데이터 넣기

캠페인 생성 API는 의도적으로 두지 않았습니다. 재고 2,000장, 이미 오픈된 캠페인을 SQL로 만듭니다(앱을 한 번 기동해 테이블이 생긴 뒤 실행).

```bash
docker compose exec -T mysql mysql --default-character-set=utf8mb4 -uroot -pcoffee coffee_coupon < coffee-coupon-api/load-test/seed.sql
# campaign_id
# 1
```

### 5. API 호출

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/coupons/{couponId}` | 발급 현황 조회 (총 수량 / 발급 수량 / 잔여 수량) |
| `POST` | `/api/coupons/{couponId}/issue-no-lock` | 발급: 락 없음 (대조군, 동시 요청 시 과발급 가능) |
| `POST` | `/api/coupons/{couponId}/issue-pessimistic` | 발급: 비관적 락 (`SELECT ... FOR UPDATE`) |
| `POST` | `/api/coupons/{couponId}/issue-optimistic` | 발급: 낙관적 락 (`@Version` + 재시도) |
| `POST` | `/api/coupons/{couponId}/issue-synchronized` | 발급: synchronized (캠페인별 JVM 락, 단일 서버에서만 유효) |

발급 요청 body는 `{"userId": <Long>}`입니다. 인증은 없고, 같은 `userId`는 캠페인당 한 번만 발급됩니다.

```bash
curl -X POST http://localhost:8080/api/coupons/1/issue-pessimistic \
  -H 'Content-Type: application/json' -d '{"userId": 1}'

curl http://localhost:8080/api/coupons/1
```

| 상황 | HTTP | `code` |
| --- | --- | --- |
| 발급 성공 | 200 | - |
| 오픈 전 | 403 | `COUPON_NOT_YET_OPEN` |
| 캠페인 없음 | 404 | `COUPON_NOT_FOUND` |
| 재고 소진 | 409 | `COUPON_SOLD_OUT` |
| 중복 발급 | 409 | `DUPLICATE_ISSUE` |
| 낙관적 락 재시도 소진 | 409 | `COUPON_ISSUE_CONFLICT` |

### API 문서 (Swagger)

앱 실행 중 아래 주소에서 요청/응답 스키마를 보고 직접 호출해볼 수 있습니다.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

### 테스트

```bash
docker compose up -d --wait   # 테스트도 실제 MySQL에 연결합니다
cd coffee-coupon-api && ./gradlew test
```

H2 같은 인메모리 DB를 쓰지 않는 이유: 비관적 락(`FOR UPDATE`)과 REPEATABLE READ 스냅샷처럼 MySQL(InnoDB) 동작에 의존하는 테스트가 있기 때문입니다.

### 부하테스트

락 전략별 과발급 여부를 k6로 비교합니다. 앱을 띄운 상태에서 아래 한 줄로 모든 전략을 차례로 측정할 수 있습니다.

```bash
coffee-coupon-api/load-test/run-all.sh
```

자세한 방법과 결과 해석은 [coffee-coupon-api/load-test/README.md](coffee-coupon-api/load-test/README.md)를 참고하세요.

### 정리

```bash
docker compose down -v   # 컨테이너와 데이터 삭제
```

## 브랜치 전략

Git Flow를 따릅니다. 자세한 내용은 [docs/BRANCHING.md](docs/BRANCHING.md)를 참고하세요.
