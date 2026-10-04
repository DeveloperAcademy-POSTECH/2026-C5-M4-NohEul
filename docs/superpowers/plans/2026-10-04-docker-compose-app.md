# docker compose로 앱과 MySQL 함께 실행 Implementation Plan

> 진행 방식: 단계마다 Claude가 변경안을 보여 주고 설명한 뒤, 허락을 받고 나서 수정한다. 커밋은 사용자가 diff를 확인하고 요청할 때 한다. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `docker compose up -d --wait` 한 번으로 MySQL과 앱이 함께 떠서 `/actuator/health`가 UP이 되고, 로컬 MySQL이 3306을 써도 충돌하지 않게 한다.

**Spec:** `docs/superpowers/specs/2026-10-04-docker-compose-app-design.md`

**Issue:** #28

## Global Constraints

- 애플리케이션 코드(Kotlin)는 바꾸지 않는다. 설정, 컨테이너, 스크립트, 문서만 바꾼다.
- 호스트에서 IDE로 앱을 실행하는 방식과 `./gradlew test`는 계속 동작해야 한다.
- 개인 `application-local.yaml`은 건드리지 않는다.
- 문서와 커밋 메시지에 "—"를 쓰지 않는다.

---

## Task 1: Dockerfile과 .dockerignore

**Files:**
- Create: `coffee-coupon-api/Dockerfile`
- Create: `coffee-coupon-api/.dockerignore`

- [x] 스펙 1절의 멀티 스테이지 Dockerfile을 작성한다 (`eclipse-temurin:17-jdk` 빌드, `eclipse-temurin:17-jre` 실행, `bootJar -x test`).
- [x] 스펙 2절의 `.dockerignore`를 작성한다.
- [x] `docker build -t coffee-coupon-api coffee-coupon-api`가 성공하는지 확인한다.
- [x] 이미지의 jar에 `application-local.yaml`이 없는지 확인한다.
- [x] 커밋: `chore: 앱 Docker 이미지(멀티 스테이지) 추가`

## Task 2: compose `app` 서비스

**Files:**
- Modify: `docker-compose.yml`

- [x] `app` 서비스를 추가한다: `build`, `SPRING_DATASOURCE_*`(내부 주소 `mysql:3306`), `127.0.0.1:8080`, `depends_on: service_healthy`.
- [x] 앱 헬스체크를 추가한다. JRE 이미지에 `curl`이 있는 것을 확인했다(2026-10-04): `curl -sf http://localhost:8080/actuator/health`.
- [x] `docker compose up -d --wait`으로 두 컨테이너가 healthy가 되고, 호스트에서 `/actuator/health`가 UP인지 확인한다.
- [x] 커밋: `feat: docker compose로 앱 컨테이너 함께 실행`

## Task 3: MySQL 호스트 포트 기본값 13306

**Files:**
- Modify: `docker-compose.yml`, `coffee-coupon-api/src/main/resources/application.yaml`, `coffee-coupon-api/load-test/run-all.sh`, `coffee-coupon-api/load-test/verify.sh`

- [x] compose MySQL 포트 기본값을 `${MYSQL_PORT:-13306}`으로 바꾼다.
- [x] `application.yaml` 기본 URL을 `localhost:13306`으로 바꾼다.
- [x] `run-all.sh`, `verify.sh`가 `MYSQL_TCP_PORT`가 없으면 13306을 쓰게 한다.
- [x] 로컬 MySQL이 3306을 쓰는 상태에서 `docker compose up -d --wait`이 충돌 없이 뜨는지 확인한다.
- [x] `run-all.sh`를 포트 지정 없이 작은 부하로 돌려 동작을 확인한다.
- [x] 앱 컨테이너를 내리고(`docker compose stop app`) `./gradlew test`가 compose MySQL로 통과하는지 확인한다. (개인 `application-local.yaml`이 있으면 그 url이 우선하므로, 확인할 때는 `SPRING_DATASOURCE_URL`로 13306을 지정한다)
- [x] 커밋: `chore: compose MySQL 호스트 포트 기본값을 13306으로 변경`

## Task 4: 문서

**Files:**
- Modify: `README.md`, `coffee-coupon-api/load-test/README.md`

- [ ] 루트 README 실행 방법을 "`docker compose up -d --wait` 한 번" 중심으로 정리하고, 개발용(MySQL만 띄우고 IDE 실행) 방법도 남긴다.
- [ ] 기본 포트(13306), 접속 정보, 시드 명령, 포트 충돌 안내를 새 기본값에 맞춘다.
- [ ] `load-test/README.md`의 포트 안내(`MYSQL_TCP_PORT`)를 새 기본값에 맞추고, 컨테이너로 측정하면 수치가 달라진다는 점을 적는다.
- [ ] 커밋: `docs: docker compose 실행 방법과 기본 포트 13306 반영`

## Task 5: PR

- [ ] `develop`으로 PR을 연다. 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 구조를 따르고 `Closes #28`을 넣는다. 올리기 전에 사용자에게 문구를 확인받는다.
