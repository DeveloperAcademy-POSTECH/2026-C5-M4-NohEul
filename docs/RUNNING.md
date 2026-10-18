# 실행 방법

모든 명령은 저장소 루트에서 실행합니다.

## 사전 준비

- Docker
- JDK 17 (IDE로 앱을 실행하거나 테스트를 돌릴 때만 필요)

MySQL 설정(호스트 포트 `13306`, `root` / `coffee`, DB `coffee_coupon`)은 루트의 `.env` 한 곳에 있고(예시 `.env.example`을 복사해서 만듦), `docker-compose.yml`과 부하테스트 스크립트가 이 파일을 읽습니다. 스프링 앱은 `.env`를 읽지 않으므로 `application.yaml`의 기본값을 같은 값으로 맞춰 두었습니다. 그래서 별도 설정 없이 동작합니다. MySQL 호스트 포트를 13306으로 둔 이유는 로컬에 설치된 MySQL(3306)과 겹치지 않게 하기 위해서입니다.

> 이 비밀번호는 로컬 개발 전용 공개 값입니다. compose의 포트는 `127.0.0.1`에만 열려 같은 네트워크의 다른 기기에서는 접속할 수 없습니다. 운영 환경에서는 반드시 환경 변수로 실제 접속 정보를 주입하세요.

## 1. 실행

```bash
cp .env.example .env   # 처음 한 번만. 로컬 MySQL 설정(.env는 gitignore 대상)
docker compose up -d --wait
```

MySQL과 앱이 함께 뜹니다. 앱은 MySQL이 준비된 뒤 시작하고, 앱의 헬스체크가 UP이 되면 명령이 끝납니다.

- 첫 실행은 이미지 안에서 Gradle 빌드를 하느라 몇 분 걸립니다. 다음부터는 캐시로 빨라집니다.
- 코드를 바꿨다면 `docker compose up -d --wait --build`로 이미지를 다시 빌드해야 반영됩니다.
- 테이블은 앱이 처음 뜰 때 자동 생성됩니다(`ddl-auto: update`).

## 2. 헬스체크

```bash
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

## 3. 테스트 데이터 넣기

캠페인 생성 API는 의도적으로 두지 않았습니다. 재고 2,000장, 이미 오픈된 캠페인을 SQL로 만듭니다(앱이 떠서 테이블이 생긴 뒤 실행).

```bash
docker compose exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < coffee-coupon-api/load-test/seed.sql
# campaign_id
# 1
```

## 4. API 호출

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

## API 문서 (Swagger)

앱 실행 중 아래 주소에서 요청/응답 스키마를 보고 직접 호출해볼 수 있습니다.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## IDE로 앱 실행하기

코드를 고치면서 디버깅할 때는 MySQL만 도커로 띄우고 앱은 IDE나 Gradle로 실행합니다. 앱 컨테이너는 코드를 바꿀 때마다 이미지를 다시 빌드해야 하고 디버거를 붙이기 번거롭기 때문입니다.

```bash
docker compose up -d --wait mysql
cd coffee-coupon-api && ./gradlew bootRun   # 또는 IDE에서 CoffeeCouponApiApplication 실행
```

## 테스트

```bash
docker compose up -d --wait mysql   # 테스트도 실제 MySQL에 연결합니다
cd coffee-coupon-api && ./gradlew test
```

H2 같은 인메모리 DB를 쓰지 않는 이유: 비관적 락(`FOR UPDATE`)과 REPEATABLE READ 스냅샷처럼 MySQL(InnoDB) 동작에 의존하는 테스트가 있기 때문입니다.

## 부하테스트

락 전략별 과발급 여부를 k6로 비교합니다. 앱을 띄운 상태에서 아래 한 줄로 모든 전략을 차례로 측정할 수 있습니다. 캠페인은 스크립트가 전략마다 새로 만들므로 3단계(테스트 데이터 넣기)는 필요 없습니다.

```bash
coffee-coupon-api/load-test/run-all.sh
```

앱을 컨테이너로 띄웠을 때와 IDE로 실행했을 때는 응답 시간과 처리량이 다르므로, 결과를 비교할 때는 실행 방법을 맞춥니다. 자세한 방법과 결과 해석은 [coffee-coupon-api/load-test/README.md](../coffee-coupon-api/load-test/README.md)를 참고하세요.

## 다른 MySQL 쓰기

직접 설치한 MySQL 등 다른 DB를 쓰려면 환경 변수로 접속 정보를 지정합니다.

```bash
export DB_URL='jdbc:mysql://localhost:3306/coffee_coupon?createDatabaseIfNotExist=true'
export DB_PASSWORD='<내 MySQL 비밀번호>'
```

- compose MySQL의 포트나 비밀번호를 바꾸려면 `.env`를 고치고, `application.yaml`의 기본값도 같이 맞춥니다. 한 번만 바꿔 띄우려면 `MYSQL_PORT=<포트> docker compose up -d --wait`처럼 환경 변수로 덮어쓸 수도 있습니다(환경 변수가 `.env`보다 우선).
- 부하테스트 스크립트(`verify.sh`, `run-all.sh`)의 `mysql` 명령은 기본으로 `.env`의 포트와 비밀번호를 씁니다. 다른 MySQL을 쓰면 `MYSQL_TCP_PORT`와 `MYSQL_PWD`를 함께 지정합니다.

## 정리

```bash
docker compose down -v   # 앱과 MySQL 컨테이너, 데이터 삭제
```
