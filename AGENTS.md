# Repository Guidelines

## Project Structure & Module Organization

This is a Maven Java 25 Spring Boot 4.1.0 project. The main build descriptor is
`pom.xml`, with the Maven wrapper available as `mvnw`. Production code lives in
`src/main/java`, tests in `src/test/java`, and Flyway database migrations in
`src/main/resources/db/migration`.

The package layout follows application boundaries: `domain` contains core
business concepts, `application` holds use cases and orchestration, and
`adapter` packages contain integrations. Current adapters include
`adapter/in/mcp`, `adapter/out/postgres`, `adapter/out/http`, and
`adapter/out/hermes`.

## Build, Test, and Development Commands

- `./mvnw test` runs the unit test suite through Surefire.
- `./mvnw verify -Pintegration-tests` runs integration tests through Failsafe.
- `./mvnw spring-boot:run` starts the application locally.
- `docker compose build mcp` builds the local container image for the persistent Streamable HTTP MCP server.
- `docker compose up -d --wait postgres mcp` starts PostgreSQL privately and publishes MCP only on host loopback; `python3 scripts/smoke-mcp-http.py` verifies initialize, discovery, and `health`.
- `./scripts/run-local-mcp-container.sh` is the explicit STDIO CI/package-verification launcher. It activates the `stdio` profile and must not be used for normal Hermes tool calls.
- `./scripts/run-mcp-stdio-diag.sh` launches a unique-named diagnostic MCP STDIO container so engineering smoke/diagnosis cannot kill an active Hermes session.
- `python3 scripts/smoke-mcp-stdio.py -- ./scripts/run-local-mcp-container.sh` verifies the containerized MCP `initialize` + `tools/list` STDIO contract for the default instance. Prefer `./scripts/run-mcp-stdio-diag.sh` when Hermes may already be connected.
- `scripts/tests/test-mcp-container-cleanup.sh` is a Docker-free regression for cleanup ownership (preserve custom instances; remove default/legacy only for default launches).

Surefire excludes `*IntegrationTests`; the integration profile enables Failsafe
and includes those tests.

## Coding Style & Naming Conventions

Follow standard Java conventions: PascalCase for types, camelCase for methods
and fields, and package names in lowercase. Keep code aligned with the existing
layered package structure. Domain code should remain independent of framework
and adapter concerns, while adapters should translate external protocols,
persistence, and HTTP/Hermes interactions into application-facing contracts.

No Spotless, Checkstyle, or `.editorconfig` configuration is currently present,
so prefer the formatting already used in nearby code.

## Testing Guidelines

Testing uses JUnit Jupiter, Spring Boot tests, Mockito, and Testcontainers with
PostgreSQL. Place fast unit tests near the classes they cover under
`src/test/java`. Name integration tests with the `IntegrationTests` suffix so
they are excluded from the default test run and included by
`./mvnw verify -Pintegration-tests`.

## Commit & Pull Request Guidelines

Use Conventional Commits, matching existing history examples such as
`feat(profile): add deterministic profile search`,
`fix(document): wrap list profiles MCP response`, and
`test: enforce coverage gate`.

Pull requests should summarize the behavior change, call out database
migrations or configuration changes, and include the exact verification command
and result. Feature/fix pull requests may target `master`; those candidates run
pipeline validation, unit tests, Docker-backed integration/coverage, a
containerized MCP STDIO smoke test, and Qodana. Pull requests promoting
`development` to `master` additionally require an identical candidate tree.
Trusted pushes to `development` and manual CI runs retain the heavy integration
and container smoke gates. Tag releases verify and publish the exact
smoke-tested image and verified jar artifacts.

## Security & Configuration Tips

Do not commit secrets, local credentials, or environment-specific connection
strings. Keep schema changes in Flyway migrations and make local configuration
overrides explicit in ignored environment files or runtime settings. Do not
hardcode paths in configuration; use environment placeholders, project-relative
safe defaults, generated runtime directories, or documented caller-supplied
settings instead of machine-local absolute paths.

The containerized MCP runtime must remain local-only: publish MCP only on host
loopback and never publish PostgreSQL. Normal Hermes use goes through the
persistent Streamable HTTP service; STDIO is reserved for isolated CI and
diagnostics. Keep Docker lifecycle output off stdout for STDIO launch scripts
because stdout is reserved for JSON-RPC messages.
