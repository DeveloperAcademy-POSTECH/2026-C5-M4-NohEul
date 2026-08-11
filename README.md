# concurrent-purchaseapi

재고 동시 차감 시 발생하는 race condition을 방지하는 방법을 학습하기 위한 프로젝트입니다.

## 프로젝트 소개

여러 사용자가 동시에 같은 상품을 구매할 때 재고 수량을 안전하게 차감하는 방법을 다룹니다.
비관적 락, 낙관적 락, 분산 락 등 다양한 동시성 제어 기법을 적용하고 비교해보는 것을 목표로 합니다.

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
- MySQL 실행 및 `src/main/resources/application.yaml`에 접속 정보 설정

### 실행

```bash
cd concurrent-purchaseapi
./gradlew bootRun
```

### 테스트

```bash
cd concurrent-purchaseapi
./gradlew test
```

## 브랜치 전략

Git Flow를 따릅니다.

| 브랜치 | 생명주기 | 분기 시작점 | 병합 대상 | 역할 |
|---|---|---|---|---|
| `main` | 영구 | - | - | 항상 배포 가능한 안정 버전. 커밋마다 릴리즈 태그(`v1.0.0` 등)를 남긴다 |
| `develop` | 영구 | `main` | - | 다음 릴리즈를 위한 통합 브랜치. 모든 기능이 여기로 모인다 |
| `feature/*` | 임시 | `develop` | `develop` | 개별 기능 개발. 완료되면 `develop`로 병합 후 삭제 |
| `release/*` | 임시 | `develop` | `main` + `develop` | 릴리즈 준비(버그 수정, 버전 정리 등). 새 기능 추가는 하지 않는다 |
| `hotfix/*` | 임시 | `main` | `main` + `develop` | 배포된 버전의 긴급 버그 수정 |

**작업 흐름**

1. `develop`에서 `feature/xxx` 분기 → 기능 개발 → `develop`로 머지
2. 릴리즈 준비가 되면 `develop`에서 `release/x.y.z` 분기 → 최종 점검 및 버그 수정
3. `release/x.y.z`를 `main`에 머지하고 태그(`vx.y.z`)를 남긴 뒤, `develop`에도 머지
4. 운영 중 긴급 버그 발견 시 `main`에서 `hotfix/xxx` 분기 → 수정 → `main`과 `develop` 양쪽에 머지

**브랜치 네이밍 예시**

- `feature/pessimistic-lock`
- `feature/optimistic-lock`
- `feature/distributed-lock`
- `release/0.1.0`
- `hotfix/stock-underflow`
