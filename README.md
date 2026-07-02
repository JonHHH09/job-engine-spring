# job-engine-spring

`job-engine-spring` is a Spring Boot / Spring AI MCP server that is replacing the legacy Python Job-Engine runtime with a Java/Spring implementation.

## Current scope

The current verified MCP surface is intentionally small:

- `health` — checks database readiness without returning connection details or secrets.
- `list_profiles` — lists saved profile identities without raw resume text or credentials.
- `get_profile` — returns a normalized profile aggregate by UUID.
- `create_profile` — creates a normalized profile aggregate.
- `update_profile` — replaces a normalized profile aggregate by UUID.
- `delete_profile` — deletes a profile aggregate and cascades owned child rows.

The application is MCP-first. Do not add REST controllers unless REST compatibility is explicitly required.

## Architecture

The project follows a Spring Boot-first hexagonal layout:

- `domain` — pure Java records/value objects with no Spring, MCP, JDBC, or persistence annotations.
- `application` — use cases, ports, transactions, and safe application errors.
- `adapter/in/mcp` — thin Spring AI MCP tool adapters.
- `adapter/out/postgres` — PostgreSQL/JDBC adapters behind application ports.

Flyway owns schema creation and evolution under `src/main/resources/db/migration`. Treat applied `V*__*.sql` migrations as immutable; add a new versioned migration for schema changes.

## Configuration

Configure the PostgreSQL connection through environment variables or safe local defaults:

```yaml
spring:
  datasource:
    url: ${JOB_ENGINE_POSTGRES_URL:jdbc:postgresql://localhost:5432/job_engine}
    username: ${JOB_ENGINE_POSTGRES_USER:}
    password: ${JOB_ENGINE_POSTGRES_PASSWORD:}
```

Never commit real credentials, private resume data, API keys, or production connection details.

For STDIO MCP, keep banner/log output off stdout so JSON-RPC messages are not polluted.

## Profile write contract

`create_profile` and `update_profile` validate profile write requests in the application layer before persistence. Invalid payloads are rejected with `validation_error` application exceptions and safe details containing only the invalid field path and reason. The profile MCP adapter maps application and unexpected failures to MCP `CallToolResult` errors with sanitized structured content instead of leaking raw exception text.

Currently validated examples include:

- non-blank `fullName` and email
- basic email shape
- required contact, link, skill, language, and project technology fields
- non-negative display order values
- education/experience date ranges
- duplicate normalized skills, languages, links, contacts, and project technologies within one request

Database constraints still protect persistence integrity, but expected request problems should fail before PostgreSQL constraint errors.

## Verification

Run the focused profile tests while working on profile behavior:

```bash
./mvnw -q -Dtest=ProfileWriteValidatorTests,ProfileServiceTests,ProfileMcpAdapterTests,PostgresProfileRepositoryIntegrationTests test
```

Run the full test suite before claiming repository-level completion:

```bash
./mvnw test
```

Use `./mvnw verify` when the JaCoCo coverage gate must be enforced.
