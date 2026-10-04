# 1단계: 빌드 (JDK 17). 호스트에 JDK가 없어도 이미지 안에서 jar를 만든다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 빌드 설정만 먼저 복사해 의존성을 받아 둔다. 설정이 안 바뀌면 이 레이어를 재사용해서 다음 빌드가 빨라진다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null

# 소스를 복사하고 jar를 만든다. 테스트는 MySQL이 필요해서 여기서는 돌리지 않는다(호스트에서 ./gradlew test).
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2단계: 실행 (JRE 17). JDK와 Gradle 캐시 없이 jar만 담는다.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
