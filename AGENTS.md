# Repository Guidelines

`job-engine-spring` is a local-only MCP (Model Context Protocol) server, not a hosted service, REST API, web UI, or job-board scraper. It stores normalized candidate profiles and jobs, produces explainable profile-to-job match reports, and generates resume/cover-letter PDFs. Normal runtime is a persistent Streamable HTTP MCP endpoint published only on host loopback (`http://127.0.0.1:8080/mcp`); STDIO exists only for CI/package verification and isolated diagnostics. The application is MCP-first: do not add REST controllers unless REST compatibility is explicitly required.

## Project Structure & Module Organization

This is a Maven Java 25 Spring Boot 4.1.0 project. The main build descriptor is
`pom.xml`, with the Maven wrapper available as `mvnw`. Production code lives in
`src/main/java`, tests in `src/test/java`, and Flyway database migrations in
`src/main/resources/db/migration`.

The package layout follows application boundaries: `domain` contains core
business concepts, `application` holds use cases and orchestration, and
`adapter` packages contain integrations. Current adapters include
`adapter/in/mcp`, `adapter/out/postgres`, and `adapter/out/http`.

## Build, Test, and Development Commands

- `./mvnw test` runs the unit test suite through Surefire.
- `./mvnw -q -Dtest=ClassName test` runs a single test class; comma-separate multiple classes, e.g. `./mvnw -q -Dtest=JobServiceTests,JobMcpAdapterTests,HttpJobLinkContentFetcherTests test`.
- `./mvnw verify -Pintegration-tests` runs integration tests through Failsafe (Testcontainers/PostgreSQL) plus the JaCoCo coverage gate. Requires Docker.
- `./mvnw spring-boot:run` starts the application locally.
- `docker compose build mcp` builds the local container image for the persistent Streamable HTTP MCP server.
- `docker compose up -d --build --wait postgres mcp` is the build-from-source dev path: starts PostgreSQL privately and publishes MCP only on host loopback; `python3 scripts/smoke-mcp-http.py` verifies initialize, discovery, and `health`.
- `./scripts/rebuild-local-mcp-jar.sh` runs `./mvnw test`, packages the jar, then runs `hermes mcp test job-engine-spring` if the Hermes CLI is present (`RUN_TESTS=false` to skip tests when already green).
- `./scripts/run-release-mcp-http.sh ghcr.io/jonhhh09/job-engine-spring:vX.Y.Z` is the guarded release-only deployment path; it refuses local/`latest` images and recreates the service without building.
- `./scripts/run-local-mcp-container.sh` is the explicit STDIO CI/package-verification launcher. It activates the `stdio` profile and must not be used for normal Hermes tool calls.
- `./scripts/run-mcp-stdio-diag.sh` launches a unique-named diagnostic MCP STDIO container so engineering smoke/diagnosis cannot kill an active Hermes session.
- `python3 scripts/smoke-mcp-stdio.py -- ./scripts/run-local-mcp-container.sh` verifies the containerized MCP `initialize` + `tools/list` STDIO contract for the default instance. Prefer `./scripts/run-mcp-stdio-diag.sh` when Hermes may already be connected.
- `scripts/tests/test-mcp-container-cleanup.sh` is a Docker-free regression for cleanup ownership (preserve custom instances; remove default/legacy only for default launches).
- After rebuilding/redeploying: recreate the persistent service, then run `/reload-mcp` in the connected agent; if tool names/schemas/prompts changed, also run `/reset` to refresh the agent's cached tool schema.
- Backup/recovery: `./scripts/postgres-backup.sh`, `./scripts/postgres-verify-backup.sh`, `./scripts/postgres-restore.sh`, `./scripts/postgres-backup-prune.sh`. Restore/verify always target a disposable Compose project/volume, never the primary one directly.

Surefire excludes `*IntegrationTests`; the integration profile enables Failsafe
and includes those tests.

## Architecture

Hexagonal layout, strictly layered:
- `domain` — pure Java records/value objects; no Spring, MCP, or JDBC annotations.
- `application` — use cases, ports, transactions, and sanitized application errors (`ApplicationException`/`ApplicationErrorCode`).
- `adapter/in/mcp` — thin Spring AI MCP tool adapters that translate requests and map failures to sanitized `CallToolResult` errors (never raw exception text).
- `adapter/out/postgres` — JDBC/PostgreSQL adapters behind application ports.
- `adapter/out/http` — outbound job-URL fetching (SSRF-hardened).

### Major subsystems

- **Profile schema** (`profile.*`) — normalized candidate profile aggregate (contacts, links, skills, languages, education, experience, projects). Writes go through `ProfileService` as the single validation/canonicalization gate; `update_profile`/`update_profile_project` require the latest `revision` and return a sanitized `conflict` on stale writes.
- **Job schema** (`job_schema.*`) — canonical job postings plus per-insertion-method provenance tables (`job_text_ingestions`, `job_link_ingestions`). Every job has exactly one provenance source matching `jobs.source_method`, enforced in the domain aggregate, the repository write path, and `V14__enforce_job_source_provenance.sql`. Link identity is split three ways: ephemeral full retrieval URL (never persisted), redacted display `url`, and canonical `normalized_url` used for dedupe (keeps only recognized ATS posting IDs like Indeed `jk` / Greenhouse `gh_jid`; strips tracking params).
- **Document/resume generation** — PDF extraction/storage (`document.*`, dedup by SHA-256), profile PDF ingestion (one-to-one provenance chain `document.blobs -> document.documents -> document.pdf_extractions -> profile.profile_pdf_sources -> profile.profiles`), and resume/cover-letter generation (master, Canadian EN/FR, German tailored resume + cover letter) under `tmp/generated-pdfs/`. All generation returns metadata only — never PDF bytes or resume body text over MCP.
- **Match analysis** (`match.*`) — deterministic `deterministic-v1` scoring (technical/experience/domain/delivery/hard-requirement components) plus advisory reviews stored separately, never replacing the baseline. `divergence-v1` policy creates deduplicated disagreements from review vs. baseline deltas; disagreements can be acknowledged or linked to an external Linear issue ID only (no Linear API integration exists).
- **Arbeitnow discovery** — `scan_arbeitnow_jobs` (read-only, bounded, public API) issues signed short-lived `candidateToken`s (HMAC-SHA-256, ≤15 min TTL, process-local key) that `import_arbeitnow_job` verifies and imports without refetching the provider.

### Cross-cutting contracts

- **MCP responses are object-shaped**, e.g. `{ "profiles": [...] }` not a bare array, so clients rejecting raw top-level arrays still validate.
- **Bounded pagination**: `list_jobs`, `list_profiles`, `analyze_all_job_matches`, `list_match_reports`, `list_match_reviews`, `list_match_disagreements` share an opaque, HMAC-authenticated keyset cursor (`created_at, id` + first-page watermark + scope fingerprint). Treat cursors as opaque; don't hand-construct them.
- **Search** (`search_profiles`, `search_jobs`) shares one canonical Unicode normalizer (`SearchTextNormalizer`: NFKD, mark-strip, lowercase, tokenize), a 256-char/16-term query limit, and bounded indexed-posting ranking (max 500 candidates). `matchedCount` is a lower bound when either bound is hit; `totalMatches` is then `null`.
- **Untrusted external content**: PDFs, job page text, and any provider/agent output are treated as untrusted data — never executed, never treated as instructions, and errors are always sanitized (no stack traces, secrets, credentials, or raw provenance details leak through MCP).
- **SSRF hardening**: job URL fetching accepts only public IP-literal HTTP(S) targets (no hostname allow-list — resolving before connecting doesn't prevent DNS-rebinding/TOCTOU), never follows redirects, and rejects local/private/metadata/userinfo targets pre-send. Use `add_job_from_text` for ordinary hostname-based job-board URLs.
- **Network boundary**: `McpLocalOnlyStartupGuard` enforces loopback-only HTTP (or the explicit container runtime marker); never publish MCP on a non-loopback bind or publish PostgreSQL. Keep STDIO stdout reserved for JSON-RPC — no banner/log output on stdout in STDIO mode.

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
and result. Normal feature/fix pull requests target `development`; those
candidates run pipeline validation, unit tests, and Qodana. Pull requests
promoting `development` to `master` additionally require an identical candidate
tree and run the Docker-backed integration/coverage and container MCP smoke
gates. Trusted pushes to `development` and manual CI runs retain the heavy
integration and container smoke gates. Tag releases verify and publish the
exact smoke-tested image and verified jar artifacts.

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
