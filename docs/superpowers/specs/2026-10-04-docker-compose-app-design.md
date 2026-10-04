# docker compose로 앱과 MySQL 함께 실행 설계

- 날짜: 2026-10-04
- 이슈: #28
- 브랜치: `feature/docker-compose-app`
- 목적: `docker compose up -d --wait` 한 번으로 MySQL과 스프링 앱이 함께 떠서 `/actuator/health`가 UP이 되게 한다. 로컬에 MySQL이 이미 3306을 쓰고 있어도 충돌 없이 실행되게 한다. 테스트(`./gradlew test`)와 IDE 실행은 지금처럼 호스트에서 그대로 쓸 수 있어야 한다.

## 범위

- `coffee-coupon-api/Dockerfile`, `coffee-coupon-api/.dockerignore` 신규 작성
- `docker-compose.yml`에 `app` 서비스 추가, MySQL 호스트 포트 기본값을 `13306`으로 변경
- 기본 포트 변경에 맞춰 `application.yaml` 기본 URL, 부하테스트 스크립트(`run-all.sh`, `verify.sh`), README 두 개 갱신
- 범위 밖
  - 운영 배포용 이미지 최적화(이미지 크기, JVM 튜닝, 이미지 레지스트리 푸시)
  - 컨테이너 환경에서 네 전략 부하테스트 재측정 (필요하면 별도로 진행)

## 1. 이미지 빌드: 멀티 스테이지 Dockerfile

```dockerfile
# 1단계: 빌드 (JDK)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null   # 의존성만 먼저 받아 레이어 캐시
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2단계: 실행 (JRE)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- **멀티 스테이지를 쓰는 이유**: 호스트에 JDK가 없어도 `docker compose up`만으로 빌드부터 실행까지 끝난다. 실행 이미지에는 JDK와 Gradle 캐시 없이 JRE와 jar만 남는다.
- **의존성을 먼저 받는 이유**: Docker는 명령 단위로 레이어를 캐시한다. 빌드 설정 파일이 안 바뀌면 의존성 다운로드 레이어를 재사용해서, 소스만 바꾼 두 번째 빌드부터 빨라진다.
- **테스트를 빌드에서 빼는 이유(`-x test`)**: 테스트는 실제 MySQL이 필요한데 이미지 빌드 단계에는 MySQL이 없다. 테스트는 지금처럼 호스트에서 `./gradlew test`로 돌린다.
- JDK 버전은 `build.gradle.kts`의 toolchain(17)과 맞춘다.

## 2. `.dockerignore`

```
build/
.gradle/
load-test/results/
src/main/resources/application-local.yaml
```

- **`application-local.yaml`을 반드시 빼는 이유**: 이 파일은 `src/main/resources`에 있어서 빌드하면 jar 안에 들어간다. `application.yaml`이 `spring.profiles.active: local`이라, jar에 들어가면 컨테이너도 이 파일을 읽고 내 PC 전용 설정(포트, 비밀번호)으로 붙으려다 실패한다. gitignore 대상이라 다른 사람 PC에는 없지만, 있는 PC에서도 같은 이미지가 나와야 한다.
- `build/`, `.gradle/`은 이미지 안에서 새로 빌드하므로 빌드 컨텍스트로 보낼 필요가 없다(전송 시간과 캐시 무효화 방지).

## 3. compose `app` 서비스

```yaml
services:
  mysql:
    # 기존 설정 유지, 호스트 포트 기본값만 13306
    ports:
      - "127.0.0.1:${MYSQL_PORT:-13306}:3306"

  app:
    build: ./coffee-coupon-api
    container_name: coffee-coupon-app
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/coffee_coupon?createDatabaseIfNotExist=true
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: coffee
    ports:
      - "127.0.0.1:8080:8080"
    depends_on:
      mysql:
        condition: service_healthy
    healthcheck:
      test: <actuator health 확인 명령>
```

- **DB 주소가 `mysql:3306`인 이유**: 컨테이너 안의 `localhost`는 컨테이너 자신이다. compose는 서비스 이름(`mysql`)으로 서로를 찾을 수 있는 내부 네트워크를 만든다. 내부 통신은 컨테이너 포트(3306)를 쓰므로, 호스트 포트(13306)와 무관하다.
- **`SPRING_DATASOURCE_*` 환경 변수를 쓰는 이유**: 환경 변수는 yaml 파일보다 우선순위가 높아서, 이미지에 어떤 설정 파일이 들어 있든 확실히 이 값이 적용된다.
- **`depends_on: condition: service_healthy`**: MySQL이 컨테이너만 뜬 상태가 아니라 헬스체크(`mysqladmin ping`)를 통과한 뒤에 앱을 시작한다. 그렇지 않으면 앱이 MySQL 준비 전에 붙으려다 실패할 수 있다.
- **앱 헬스체크**: `docker compose up --wait`이 앱이 실제로 요청을 받을 수 있을 때(`/actuator/health`가 UP)까지 기다리게 한다. JRE 이미지에 `curl`이 있는지는 구현할 때 확인하고, 없으면 대체 방법을 쓴다.
- **포트는 `127.0.0.1`에만 연다**: MySQL과 같은 원칙. 같은 네트워크의 다른 기기에서는 접속할 수 없다.

## 4. MySQL 호스트 포트 기본값 13306

호스트에서 MySQL에 직접 붙는 곳(`./gradlew test`, IDE 실행, 부하테스트 시딩과 검증)은 여전히 열린 포트가 필요하다. 기본값을 3306으로 두면 로컬에 MySQL이 설치된 PC에서 충돌하므로, 기본값을 `13306`으로 바꾼다.

| 대상 | 변경 |
|---|---|
| `docker-compose.yml` | `${MYSQL_PORT:-3306}` → `${MYSQL_PORT:-13306}` |
| `application.yaml` | 기본 URL `localhost:3306` → `localhost:13306` (`DB_URL`로 덮어쓰기는 그대로 가능) |
| `run-all.sh`, `verify.sh` | `MYSQL_TCP_PORT`가 없으면 `13306`을 기본으로 쓴다. `mysql` 명령은 지정하지 않으면 3306에 붙기 때문 |
| README, `load-test/README.md` | 기본 포트와 접속 예시를 13306으로 |

- 앱 컨테이너는 내부 네트워크로 붙으므로 이 변경과 무관하다.
- 직접 설치한 MySQL(3306)을 쓰고 싶으면 지금처럼 `DB_URL`이나 `application-local.yaml`, `MYSQL_TCP_PORT=3306`으로 지정한다.
- 이미 떠 있는 compose MySQL 컨테이너는 포트 매핑이 바뀌므로 `docker compose up`이 다시 만든다. 데이터는 익명 볼륨이라 개발용으로 다시 시딩하면 된다.

## 5. 실행 흐름 (README)

```bash
docker compose up -d --wait        # MySQL + 앱 빌드·실행, 앱 헬스체크 UP까지 대기
curl http://localhost:8080/actuator/health
coffee-coupon-api/load-test/run-all.sh
docker compose down -v
```

- 개발 중에는 지금처럼 `docker compose up -d --wait mysql`로 MySQL만 띄우고 IDE나 `./gradlew bootRun`으로 앱을 실행할 수 있다. 두 방식을 함께 안내한다.
- 앱과 IDE 실행을 동시에 하면 8080이 겹치므로, 한쪽만 띄운다.

## 6. 알려진 영향과 한계

- **부하테스트 수치**: 맥의 Docker Desktop은 가상 머신 위에서 돌아서, 앱을 컨테이너로 띄우면 지연과 처리량이 IDE 실행과 달라진다. 2026-10-04 결과 표(앱은 IDE 실행)와 직접 비교하지 않고, 측정할 때 실행 환경을 함께 적는다.
- **첫 빌드가 느리다**: Gradle 배포판과 의존성을 이미지 안에서 처음 받는다. 두 번째부터는 레이어 캐시로 빨라진다.
- **코드를 바꾸면 이미지를 다시 빌드해야 한다**: `docker compose up -d --build`. 개발 중 빠른 반복은 IDE 실행을 권장한다.
- **개인 `application-local.yaml`**: 이 파일을 쓰는 PC는 파일의 url이 우선하므로, compose MySQL을 쓰려면 파일의 포트를 13306으로 맞추거나 파일을 치운다.

## 7. 검증

- 깨끗한 상태(`docker compose down -v`, 로컬 이미지 없음)에서 `docker compose up -d --wait` 한 번으로 두 컨테이너가 healthy, `/actuator/health`가 UP
- 로컬 MySQL이 3306을 쓰는 상태에서도 충돌 없이 실행
- `run-all.sh`가 기본 설정(포트 지정 없음)으로 동작
- 앱 컨테이너를 내리고 `./gradlew test`가 compose MySQL(13306)로 통과
- 이미지 안에 `application-local.yaml`이 없는지 확인 (`docker cp coffee-coupon-app:/app/app.jar /tmp/app.jar && unzip -l /tmp/app.jar | grep application`. JRE 이미지에는 `jar` 명령이 없어서 호스트로 꺼내서 본다)

## 결정된 사항 요약

- 빌드: 멀티 스테이지 Dockerfile (JDK 17로 빌드, JRE 17로 실행, 테스트는 빌드에서 제외)
- 앱 → DB: compose 내부 네트워크의 `mysql:3306`, `SPRING_DATASOURCE_*` 환경 변수로 지정
- MySQL 호스트 포트 기본값: 3306 → 13306 (로컬 MySQL과 충돌 방지), 관련 기본값 일괄 변경
- 앱 헬스체크: 추가 (`--wait`이 UP까지 대기)
- `application-local.yaml`: `.dockerignore`로 이미지에서 제외
