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
- `docker compose build mcp` builds the local container image for the STDIO MCP server.
- `./scripts/run-local-mcp-container.sh` runs the local MCP server in a Docker container over stdin/stdout, with PostgreSQL started by Compose and no published MCP port.
- `python3 scripts/smoke-mcp-stdio.py -- ./scripts/run-local-mcp-container.sh` verifies the containerized MCP `initialize` + `tools/list` STDIO contract.

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
and result. GitHub Actions runs fast unit tests on pull requests. Trusted pushes
to `development`/`master` and manual CI runs add Docker-backed integration tests
and a containerized MCP STDIO smoke test. Tag releases verify and publish one
jar artifact, then build the release image from that same verified jar.

## Security & Configuration Tips

Do not commit secrets, local credentials, or environment-specific connection
strings. Keep schema changes in Flyway migrations and make local configuration
overrides explicit in ignored environment files or runtime settings. Do not
hardcode paths in configuration; use environment placeholders, project-relative
safe defaults, generated runtime directories, or documented caller-supplied
settings instead of machine-local absolute paths.

The containerized MCP runtime must remain local-only: do not publish the MCP
container or PostgreSQL ports unless the architecture is intentionally changed.
Keep Docker lifecycle output off stdout for MCP launch scripts because stdout is
reserved for JSON-RPC messages.
