# Contributing to job-engine-spring

Thank you for helping improve `job-engine-spring`. This project is a local-only MCP backend for job, profile, match-analysis, document, and resume workflows. It is not a public web service or REST API.

## Before you start

- Read the [README](README.md), [Security Policy](SECURITY.md), and [Code of Conduct](CODE_OF_CONDUCT.md).
- Search existing issues before opening a new one.
- Use synthetic data in issues, tests, logs, and examples. Never submit real resumes, profile records, job applications, database backups, credentials, private keys, or production logs.
- Report suspected vulnerabilities privately as described in [SECURITY.md](SECURITY.md), not through a public issue or pull request.
- Keep changes focused. Do not combine unrelated refactors, dependency upgrades, schema changes, and feature work.

## Development setup

Required for the containerized development path:

- Git
- Docker Engine or Docker Desktop with Docker Compose v2

Java 25 is also required to run Maven directly on the host.

```bash
git clone https://github.com/JonHHH09/job-engine-spring.git
cd job-engine-spring
cp .env.example .env
docker compose up -d --build --wait postgres mcp
python3 scripts/smoke-mcp-http.py
```

The supported network boundary is `http://127.0.0.1:8080/mcp`. PostgreSQL must remain unpublished. Do not change the host bind to a non-loopback address without an explicit authenticated-network design.

## Architecture and design constraints

The project uses a Spring Boot-first hexagonal architecture:

- `domain` contains framework-free records and value objects.
- `application` contains use cases, ports, validation, transactions, and safe application errors.
- `adapter/in/mcp` contains thin MCP adapters.
- `adapter/out` contains PostgreSQL, HTTP, and provider integrations.

Additional constraints:

- Keep the application MCP-first; do not add REST controllers without an approved compatibility requirement.
- Treat PDFs, resumes, job pages, provider output, and MCP arguments as untrusted data.
- Keep errors and logs free of secrets, private document text, connection details, and stack traces returned to clients.
- Add a new Flyway migration for schema changes. Applied `V*__*.sql` migrations are immutable.
- Preserve loopback-only Streamable HTTP as the normal transport. STDIO is for isolated CI, package verification, and diagnostics.
- Job URL fetching intentionally accepts only public IP-literal HTTP(S) targets. Do not weaken the SSRF boundary with hostname pre-resolution or a hostname allow-list.

## Branches and pull requests

1. Branch from the latest `development`.
2. Use a focused branch name such as `123-short-description` or a tracker-keyed equivalent.
3. Use Conventional Commits when maintainers ask you to prepare commits.
4. Open normal feature and fix pull requests against `development`. Maintainers promote a verified `development` tree to `master` separately.
5. Complete the pull-request template with exact verification commands and results.
6. Call out Flyway migrations, configuration changes, public MCP schema changes, security impact, and release impact explicitly.

Do not commit generated PDFs, imported documents, database data, backups, populated environment files, IDE state, or credentials.

## Verification

Run the narrowest relevant tests first, then the unit suite:

```bash
./mvnw test
```

For persistence, Flyway, JDBC, startup security, coverage-sensitive behavior, or container/runtime changes, also run:

```bash
./mvnw -Pintegration-tests verify
```

For pipeline, Compose, shell, Python, or MCP transport changes, run the applicable repository checks documented in [AGENTS.md](AGENTS.md) and verify both Streamable HTTP and isolated STDIO paths. Before submitting any pull request, run:

```bash
git diff --check
git status --short
```

If a required check cannot run in your environment, state that limitation; do not claim it passed.

## Review expectations

Reviewers prioritize correctness, privacy, security boundaries, migration safety, compatibility, tests, and maintainability. Expect requests for focused regression tests and evidence from the real runtime path. A green unit suite alone is not sufficient for persistence, transport, backup/restore, or release-pipeline changes.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
