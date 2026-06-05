FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradle gradle
COPY gradlew gradlew.bat ./
RUN chmod +x gradlew
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon -q
COPY src src
RUN ./gradlew bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
