## 프로젝트 소개

선착순 커피 쿠폰 발급 API에서 락 없음, 비관적 락, 낙관적 락, synchronized(JVM 락) 네 가지 동시성 전략을 동일 조건에서 비교한 프로젝트입니다.
목표는 "어떤 락이 더 좋다"를 가리는 게 아니라, 트래픽 규모와 서비스가 허용할 수 있는 지연에 따라 적합한 전략이 달라진다는 걸 직접 검증하는 것입니다.

## 기술 스택

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-6DB33F?style=flat-square&logo=spring&logoColor=white) ![Spring MVC](https://img.shields.io/badge/Spring%20MVC-6DB33F?style=flat-square&logo=spring&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white) ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=flat-square&logo=junit5&logoColor=white)

## 트래픽 가정과 검증 방법

- 30만 명 대상, 시도율 20%, 오픈 직후 10초 집중 트래픽을 가정해 peak QPS를 추정
- 로컬 환경 한계에 맞춰 500 QPS 부하로 재현 (k6, seed.sql, verify.sh로 테스트 하네스 구성)

## 실험 결과

| 전략 | 평균 응답 | 발급 수량 |
| --- | --- | --- |
| 락 없음 | 3ms | 2,001건 성공 (재고 2,000장 대비 1장 과발급) |
| 비관적 락 | 66ms | 정확히 2,000장 |
| 낙관적 락 | 153ms | 정확히 2,000장 (단, 요청 집중 시 재시도 반복으로 지연·DB 부하 증가) |
| synchronized | 1.01초 | 정확히 2,000장 (단, 발급 전체를 한 줄로 세워 가장 느림, 단일 서버에서만 유효) |

측정: 2026-10-04, Apple M5 Mac(10코어: 성능 4 + 효율 6, 메모리 24GB) 한 대에서 앱(JDK 17, IDE에서 직접 실행), MySQL 8.4(docker), k6를 함께 실행, 500 req/s × 10초. 측정 조건과 상세 수치는 [load-test/README.md](coffee-coupon-api/load-test/README.md) 참고.

## 발견한 문제와 해결

- 락 없이 구현했을 때 카운터는 정상으로 보였지만 실제 발급 행이 재고를 초과. 화면 결과만으로는 정합성을 보장할 수 없다는 걸 확인
- 재시도 트랜잭션이 이전 트랜잭션과 얽혀 유령 행이 남고 재고가 초과 소모되는 문제 발견 → `REQUIRES_NEW`로 격리
- 이 과정에서 생긴 커넥션 풀 고갈·타임아웃을 재시도 루프 구조에서 원인을 찾아 해결
- AI가 제안한 `refresh()` 방식은 MySQL REPEATABLE READ 특성상 통하지 않는다는 걸 직접 실험으로 확인한 뒤 기각

## 결론

하나의 실험 결과만으로 더 나은 기술을 단정할 수 없습니다. 기술 자체보다 사용자 수, 요청이 몰리는 시점, 서비스가 허용할 수 있는 지연을 기준으로 전략을 선택해야 한다는 걸 확인했습니다.

## 기술 블로그

- [synchronized를 썼는데 왜 깨질까? 실험 6개로 뜯어본 JVM 락](https://0sunset0.tistory.com/17)
- [락 보관함은 왜 ConcurrentHashMap이어야 했을까?](https://0sunset0.tistory.com/18)

## 빠르게 실행하기

Docker만 있으면 됩니다. 저장소 루트에서 실행합니다.

```bash
cp .env.example .env   # 처음 한 번만
docker compose up -d --wait
```

테스트 데이터 넣기, API 호출, 테스트, 부하테스트 등 자세한 방법은 [docs/RUNNING.md](docs/RUNNING.md)를 참고하세요.

## 브랜치 전략

Git Flow를 따릅니다. 자세한 내용은 [docs/BRANCHING.md](docs/BRANCHING.md)를 참고하세요.
