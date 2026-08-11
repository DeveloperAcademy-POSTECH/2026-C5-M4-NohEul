# concurrent-purchaseapi

재고 동시 차감 시 발생하는 race condition을 방지하는 방법을 학습하기 위한 프로젝트입니다.

## 프로젝트 소개

여러 사용자가 동시에 같은 상품을 구매할 때 재고 수량을 안전하게 차감하는 방법을 다룹니다.
비관적 락, 낙관적 락, 분산 락 등 다양한 동시성 제어 기법을 적용하고 비교해보는 것을 목표로 합니다.

## 기술 스택

- Kotlin
- Spring Boot 4.1.0
- Spring Data JPA
- Spring Web MVC
- Spring Validation
- MySQL
- Gradle (Kotlin DSL)
- JUnit 5

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
