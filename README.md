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

- 락 없이 구현했을 때 카운터는 정상으로 보였지만 실제 발급 행이 재고를 초과 — 화면 결과만으로는 정합성을 보장할 수 없다는 걸 확인
- 재시도 트랜잭션이 이전 트랜잭션과 얽혀 유령 행이 남고 재고가 초과 소모되는 문제 발견 → `REQUIRES_NEW`로 격리
- 이 과정에서 생긴 커넥션 풀 고갈·타임아웃을 재시도 루프 구조에서 원인을 찾아 해결
- AI가 제안한 `refresh()` 방식은 MySQL REPEATABLE READ 특성상 통하지 않는다는 걸 직접 실험으로 확인한 뒤 기각

## 결론

하나의 실험 결과만으로 더 나은 기술을 단정할 수 없습니다. 기술 자체보다 사용자 수, 요청이 몰리는 시점, 서비스가 허용할 수 있는 지연을 기준으로 전략을 선택해야 한다는 걸 확인했습니다.

## 기술 스택

- ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
- ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white)
- ![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-6DB33F?style=flat-square&logo=spring&logoColor=white)
- ![Spring MVC](https://img.shields.io/badge/Spring%20MVC-6DB33F?style=flat-square&logo=spring&logoColor=white)
- ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white)
- ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)
- ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=flat-square&logo=junit5&logoColor=white)

## 실행 방법

### 사전 준비

- JDK 17
- MySQL 실행 후 `coffee-coupon-api/src/main/resources/application-local.yaml.example`을 같은 디렉토리에 `application-local.yaml`로 복사하고 실제 MySQL 접속 정보(비밀번호 등)를 입력하세요.

### 실행

```bash
cd coffee-coupon-api
./gradlew bootRun
```

### 테스트

```bash
cd coffee-coupon-api
./gradlew test
```

## 브랜치 전략

Git Flow를 따릅니다. 자세한 내용은 [docs/BRANCHING.md](docs/BRANCHING.md)를 참고하세요.
