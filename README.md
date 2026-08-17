# coffee-coupon-api

선착순 커피 쿠폰 발급 시 발생하는 race condition을 방지하는 방법을 학습하기 위한 프로젝트입니다.

## 프로젝트 소개

정해진 수량의 커피 쿠폰을 여러 사용자가 동시에 요청할 때, 수량을 초과해서 발급되지 않도록 안전하게 처리하는 방법을 다룹니다.
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
