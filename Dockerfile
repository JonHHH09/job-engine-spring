# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM eclipse-temurin:25-jdk@sha256:68868d04fa9cfd5f5c6abec0b5cef86d8de2bf9c62c37c7d3e4f0f80f5cfd7ff AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --no-transfer-progress -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre@sha256:d0eb1b9018b3044da1b7346f39e945f71095749853d69a3aa16b8c99dad9bb45

ENV SPRING_DOCKER_COMPOSE_ENABLED=false \
    JOB_ENGINE_DOCUMENT_IMPORT_ROOT=/app/tmp/imports \
    JOB_ENGINE_MCP_BIND_ADDRESS=0.0.0.0 \
    JOB_ENGINE_MCP_CONTAINERIZED=true

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app

COPY --from=build /workspace/target/job-engine-spring-0.0.1-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /app/tmp/imports /app/tmp/generated-pdfs \
    && chown -R app:app /app

USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD curl --silent --show-error --max-time 2 --output /dev/null http://127.0.0.1:8080/mcp || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
