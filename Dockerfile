# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --no-transfer-progress -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre

ENV SPRING_DOCKER_COMPOSE_ENABLED=false \
    JOB_ENGINE_DOCUMENT_IMPORT_ROOT=/app/tmp/imports

RUN useradd --uid 10001 --create-home --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app

COPY --from=build /workspace/target/job-engine-spring-0.0.1-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /app/tmp/imports /app/tmp/generated-pdfs \
    && chown -R app:app /app

USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
