# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM eclipse-temurin:25-jdk@sha256:c42fecf62f32725c65cfea284c012526d6fb31cc78123c740ebdc1cfd2dced12 AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --no-transfer-progress -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre@sha256:a214efa3200af4b657e41935799aa12d7aee3336fdb42eb505a0948f6ecdd983

ENV SPRING_DOCKER_COMPOSE_ENABLED=false \
    JOB_ENGINE_DOCUMENT_IMPORT_ROOT=/app/tmp/imports

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
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=12 \
    CMD curl --silent --show-error --max-time 2 --output /dev/null http://127.0.0.1:8080/mcp || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
