# job-engine-spring

`job-engine-spring` is a local-only Model Context Protocol (MCP) server for storing normalized candidate profiles and jobs, producing explainable profile-to-job match reports, and generating resume documents. It is built with Java 25, Spring Boot, Spring AI, PostgreSQL, and Flyway.

The project is an MCP backend, not a hosted service, web UI, public REST API, or job-board scraper. Normal operation uses Streamable HTTP published only on host loopback. PostgreSQL is private to Docker Compose, and isolated STDIO exists only for CI, package verification, and diagnostics.

## Five-minute quickstart

### Prerequisites

- Git
- Docker Engine or Docker Desktop with Docker Compose v2
- Python 3 for the bundled MCP smoke client
- Java 25 only when running Maven directly on the host

Clone and start the source-built service:

```bash
git clone https://github.com/JonHHH09/job-engine-spring.git
cd job-engine-spring
cp .env.example .env
docker compose up -d --build --wait postgres mcp
python3 scripts/smoke-mcp-http.py
```

The smoke command initializes an MCP session, discovers the tool surface, calls `health`, and fails if the database is not ready. The supported endpoint is:

```text
http://127.0.0.1:8080/mcp
```

Do not publish the MCP port on a non-loopback host address, and do not publish PostgreSQL. The values in `.env.example` are synthetic local-development defaults; keep the copied `.env` file private and replace its password before using the environment for anything beyond isolated local development.

### Connect an MCP client

Configure a Streamable HTTP MCP server named `job-engine-spring` with the URL above. For Hermes Agent:

```yaml
mcp_servers:
  job-engine-spring:
    url: http://127.0.0.1:8080/mcp
    enabled: true
    timeout: 120
    connect_timeout: 60
```

Reload the client's MCP connections, then call `health`. A successful response reports database readiness without returning credentials or connection details.

### First useful workflow

Use synthetic text for the first job insertion; ordinary hostname job-board URLs are intentionally unsupported by direct link ingestion because URL fetching is restricted to connection-bound public IP-literal targets.

Call `add_job_from_text` with content similar to:

```text
Senior Java Engineer at Example Corp in Berlin. Build local backend services
with Java 25, Spring Boot, PostgreSQL, Docker, and MCP. Five years of backend
experience required. Full-time.
```

Optionally provide structured fields such as title, company, location, skills, seniority, and employment type. Then call `list_jobs` or `search_jobs` to read the normalized result. Do not use real resumes, private applications, credentials, or production data while evaluating the project.

### Persistence and lifecycle

PostgreSQL data persists in the Compose-managed `postgres-data` volume.

Stop containers while preserving data:

```bash
docker compose down
```

Update a source checkout without deleting the database volume:

```bash
git pull --ff-only
docker compose up -d --build --force-recreate --wait postgres mcp
python3 scripts/smoke-mcp-http.py
```

Create a transaction-consistent backup before significant upgrades:

```bash
./scripts/postgres-backup.sh
```

Destructively uninstall the local containers **and delete the PostgreSQL volume**:

```bash
docker compose down -v
```

That final command permanently deletes local application data. See [PostgreSQL backup and recovery](#postgresql-backup-and-recovery) before using it on data you need to keep.

## Project status and non-goals

- The server is under active development; MCP schemas and persisted data contracts may evolve between pre-1.0 releases.
- Only the latest published release receives security fixes.
- The supported network model is local-only. Exposing MCP or PostgreSQL to another machine is unsupported.
- Provider-backed enrichment is optional. Deterministic storage, search, and matching remain independently usable.
- Direct hostname URL ingestion is deliberately unavailable; use `add_job_from_text` for normal job-board content.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and pull-request requirements, [SUPPORT.md](SUPPORT.md) for issue routing, [SECURITY.md](SECURITY.md) for private vulnerability reports, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.

## Persistent match analysis

The service persists immutable, explainable profile-to-job match reports in the `match` PostgreSQL schema. The deterministic `deterministic-v1` baseline uses technical (40), experience/seniority (25), domain (15), delivery (10), and hard-requirement (10) components. Missing structured evidence is `UNKNOWN`, reduces confidence, and is never replaced with unstructured domain or delivery inference. Scores are normalized over known evidence, so known evidence can produce `STRONG_MATCH` while confidence remains lower. A job skill's `required` flag alone is not blocker provenance.

Advisory reviews are stored separately and never replace the baseline. Review summaries use only `review_consistent`, `score_adjustment`, `outcome_adjustment`, or `evidence_defect_identified`; free-form summaries are rejected. Advisory evidence must exactly reuse a fact from the deterministic baseline or use one of `structured_evidence_missing`, `structured_evidence_incorrect`, `requirement_provenance_missing`, or `outcome_calibration_issue`. Divergence policy `divergence-v1` creates a deduplicated disagreement for an overall delta of at least 15, an outcome or blocker mismatch, a component delta of at least 40% of available points, or an explicit outcome-changing evidence defect. Its fingerprint uses the report, persisted policy version, sorted reason classification, and canonical outcome-changing defect codes—not review identity, reviewer, model, or summary. Disagreements support `PENDING_ESCALATION`, acknowledgement, and linkage to an existing Linear issue ID; this application has no Linear provider integration or credentials.

MCP tools: `analyze_job_match`, `analyze_all_job_matches`, `get_match_report`, `list_match_reports`, `submit_match_review`, `get_match_review`, `list_match_reviews`, `list_match_disagreements`, `acknowledge_match_disagreement`, and `link_match_disagreement`. Requests are object-shaped. Acknowledgement and linkage can record an existing Linear issue ID but never call Linear or use provider credentials. Responses contain normalized evidence only and exclude contacts, resume documents/text, prompts, reasoning traces, secrets, provider transcripts, and private URLs.

## Current scope

The current verified MCP surface is intentionally small:

- `health` — checks database readiness without returning connection details or secrets.
- `list_profiles` — lists saved profile identities in a `{ "profiles": [...] }` wrapper without raw resume text or credentials.
- `search_profiles` — searches normalized profile identities by query terms across profile fields and owned child collections without returning raw resume text.
- `get_profile` — returns a normalized profile aggregate by UUID.
- `create_profile` — creates a normalized profile aggregate.
- `update_profile` — replaces a normalized profile aggregate by UUID.
- `delete_profile` — deletes a profile aggregate and cascades owned child rows.
- `extract_pdf_text` — extracts text from a local PDF file without storing content or following embedded instructions.
- `store_document_file` — stores a local document file in PostgreSQL and returns metadata only.
- `get_document_metadata` — returns stored document metadata by UUID without returning binary content.
- `extract_stored_pdf_text` — extracts text from a stored PDF and optionally persists bounded extracted text.
- `generate_pdf_file` — generates a PDF file under `tmp/generated-pdfs/` and returns file metadata.
- `generate_pdf_resume` — generates a master resume PDF from a normalized profile, stores it as a document, and links it uniquely to that profile as the `master_resume` variant.
- `generate_canadian_pdf_resume` — generates a Canadian-format resume PDF from the same normalized profile, stores it as a document, and links it uniquely to that profile as the `canadian_resume` variant.
- `generate_german_tailored_resume` — generates a Germany-format tailored Lebenslauf for one profile + job as bilingual EN+DE structured content and PDFs, linked under one `resume.resumes` parent (`format=germany`). Uses offline pure-Java translation; projects are omitted unless `includeProjects=true`; content is reviewed before PDF generation; PDF filenames use `germany_{candidate}_{number}_{lang}_{id}.pdf`.
- `ingest_profile_from_stored_pdf` — populates the normalized profile schema from a stored PDF extraction and links the profile to that extraction.
- `get_profile_pdf_source` — returns the one-to-one PDF extraction source link for a profile.
- `list_jobs` — lists stored job postings without returning source ingestion raw text.
- `search_jobs` — searches stored jobs by title, company, location, description, experience requirement, seniority, employment type, and required skills.
- `get_job` — returns a stored job aggregate by UUID, including normalized skills and insertion provenance.
- `update_job` — partially updates a stored job; omitted fields preserve current values, while a provided skills list replaces existing skills.
- `delete_job` — hard-deletes a stored job by UUID.
- `add_job_from_text` — inserts a job from pasted text plus optional structured fields; source text is hashed for idempotency and is not stored raw in the method-specific table.
- `add_job_from_link` — inserts a job from a public IP-literal HTTP(S) URL by fetching page content and combining extracted page text with optional structured fields. Hostname URLs are rejected until the HTTP client can bind validation to the address it actually connects to. If the page fetch is blocked, returns HTTP 4xx/5xx, or resolves to bot/security-check content, the tool rejects the insertion instead of creating a false job; use `add_job_from_text` for ordinary job-board URLs. The caller’s full retrieval URL is used only for the fetch itself and is never persisted or returned over MCP.
- `analyze_job_link` — fetches a job URL, calls the configured Hermes analysis provider, persists only redacted URL provenance plus the structured Hermes response in `job_schema.job_analysis_runs`, and returns an analysis-run report without creating a job.
- `add_job_from_analysis` — creates or reuses a normalized job by reading a previously stored Hermes analysis run from `job_schema.job_analysis_runs`.

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
  main:
    web-application-type: servlet
  datasource:
    url: ${JOB_ENGINE_POSTGRES_URL:jdbc:postgresql://localhost:5432/job_engine}
    username: ${JOB_ENGINE_POSTGRES_USER:}
    password: ${JOB_ENGINE_POSTGRES_PASSWORD:}
job-engine:
  document:
    import-root: ${JOB_ENGINE_DOCUMENT_IMPORT_ROOT:tmp/imports}
```

Never commit real credentials, private resume data, API keys, production connection details, or machine-local absolute paths. Configuration paths must not be hardcoded; use environment placeholders, documented safe relative defaults, or caller-supplied runtime settings.

MCP is local-only. Normal operation uses a persistent Streamable HTTP MCP container published only on the host loopback interface. `McpLocalOnlyStartupGuard` accepts loopback HTTP and the explicitly marked container runtime while rejecting unsafe transport/bind combinations. Tool schemas do not carry per-call access tokens; the security boundary is the loopback-only Docker publication plus the local OS boundary. STDIO remains available only through the `stdio` Spring profile for CI, release verification, and isolated engineering diagnosis. Local file imports for PDF extraction/storage are restricted to `job-engine.document.import-root` by default; generated resume PDFs bypass that import-root check because they are produced internally under `tmp/generated-pdfs/`. Job URL fetching is SSRF-hardened: redirects are not followed, local/private/metadata/userinfo targets are rejected before send, and only public IP-literal hosts are accepted so the validated address is the address used by the connection. Hostname allow-list configuration was intentionally removed because resolving a hostname before `HttpClient` connects does not prevent DNS-rebinding/TOCTOU attacks.

For STDIO MCP, keep banner/log output off stdout so JSON-RPC messages are not polluted.

## Local persistent MCP deployment

The normal local deployment is a persistent Spring Boot Streamable HTTP MCP container. Docker owns the application lifecycle and Hermes connects to `http://127.0.0.1:8080/mcp`, so reconnecting the client does not replace the application container.

Build and verify the local jar with:

```bash
./scripts/rebuild-local-mcp-jar.sh
```

The script runs `./mvnw test`, packages `target/job-engine-spring-0.0.1-SNAPSHOT.jar`, and then runs `hermes mcp test job-engine-spring` when the Hermes CLI is available. To skip unit tests after they have already passed, run:

```bash
RUN_TESTS=false ./scripts/rebuild-local-mcp-jar.sh
```

Rebuilding a jar or pulling an image updates an artifact on disk only. Recreate the persistent service to deploy it, then run `/reload-mcp` in the active Hermes session. If tool names, argument schemas, prompts, or resources changed, start a fresh Hermes session with `/reset` after reloading so the tool schema in the agent context is current.

`scripts/restart-local-mcp-server.sh` is kept as a local maintenance helper because it stops stale matching local jar subprocesses, rebuilds the jar without recursively running the MCP smoke test, and optionally runs the server in foreground STDIO mode. It remains local-only and does not expose an HTTP daemon.

## Local containerized MCP deployment

`compose.yaml` starts private PostgreSQL plus the persistent MCP service. PostgreSQL has no host port. MCP publishes container port 8080 only as `127.0.0.1:${JOB_ENGINE_MCP_PORT:-8080}` and uses `restart: unless-stopped`.

Build the local image when application code changes:

```bash
docker compose build mcp
```

Run and verify the persistent MCP service:

```bash
docker compose up -d --wait postgres mcp
python3 scripts/smoke-mcp-http.py
```

For Hermes, configure an HTTP MCP server URL instead of a launch command:

```yaml
mcp_servers:
  job-engine-spring:
    url: http://127.0.0.1:8080/mcp
    enabled: true
    timeout: 120
    connect_timeout: 60
```

For a published release, use the guarded deployment helper with a version tag or immutable digest. It refuses local images and `latest`, pulls the package, and explicitly recreates the persistent service without building:

```bash
./scripts/run-release-mcp-http.sh ghcr.io/jonhhh09/job-engine-spring:vX.Y.Z
python3 scripts/smoke-mcp-http.py
```

After changing the deployed image, run `/reload-mcp`. Pulling alone never changes a running container.

### Isolated STDIO verification

The legacy `scripts/run-local-mcp-container.sh` launcher explicitly activates the `stdio` profile. It is retained for CI and release-package checks. Interactive diagnosis must use the unique-name launcher below so it cannot remove the persistent Compose `mcp` service.

For diagnosis or parallel smoke while Hermes is connected, use the safe diagnostic launcher (unique container name by default):

```bash
./scripts/run-mcp-stdio-diag.sh
MCP_CONTAINER_BUILD=never python3 scripts/smoke-mcp-stdio.py -- ./scripts/run-mcp-stdio-diag.sh
```

Recovery when Hermes reports MCP unreachable:

1. Check `docker compose ps mcp postgres` and the persistent service health.
2. Run `python3 scripts/smoke-mcp-http.py` to verify the transport independently.
3. Run `/reload-mcp` once and call native `health` before CRUD tools.
4. Use `run-mcp-stdio-diag.sh` only for release/engineering diagnosis.

Host-visible local file imports are mounted from `tmp/imports/` into the container as read-only files, and generated PDFs are mounted through `tmp/generated-pdfs/`. Paths outside that import root (for example Hermes document cache under `~/.hermes/cache/documents/`) are not visible inside the MCP container; copy files into `tmp/imports/` before `store_document_file`. Never change the MCP host bind from loopback or publish the database port without a separate authenticated-network architecture decision.

Smoke-test the containerized MCP transport with:

```bash
# Normal persistent transport:
python3 scripts/smoke-mcp-http.py

# Explicit isolated STDIO verification:
MCP_CONTAINER_BUILD=never python3 scripts/smoke-mcp-stdio.py -- ./scripts/run-mcp-stdio-diag.sh
```

Stop the local database and remove its development volume when you intentionally want a clean local container database:

```bash
docker compose down -v
```

## PostgreSQL backup and recovery

Host-side scripts create private PostgreSQL custom-format backup sets outside Docker volumes. Scheduling is deliberately disabled: run these commands explicitly from a trusted host. Backup data and metadata remain under the ignored `backups/postgres/` boundary by default.

Create an atomic transaction-consistent backup:

```bash
./scripts/postgres-backup.sh
```

Verify a backup only with a released immutable MCP image. Verification creates and removes a uniquely labelled disposable Compose project; it never restores into the primary volume:

```bash
./scripts/postgres-verify-backup.sh \
  --backup-set backups/postgres/<timestamped-set> \
  --image registry.example/job-engine@sha256:<64-hex-digest>
```

Emergency restore is intentionally explicit and destructive only for a separately started, non-primary Compose project/volume. The command verifies the checksum and Compose/Docker labels before mutation; there is no primary-target bypass:

```bash
./scripts/postgres-restore.sh \
  --backup-set backups/postgres/<timestamped-set> \
  --target-project <disposable-project> \
  --target-volume <disposable-project>_postgres-data \
  --database job_engine \
  --confirm RESTORE --confirm-existing OVERWRITE
```

Preview retention before deletion; pruning considers only checksum-valid, released-image-verified sets and always keeps the newest verified set:

```bash
./scripts/postgres-backup-prune.sh --daily 7 --weekly 4 --monthly 12
./scripts/postgres-backup-prune.sh --daily 7 --weekly 4 --monthly 12 --confirm PRUNE
./scripts/postgres-diagnostics.sh
```

For a disposable Docker acceptance check, run `bash scripts/tests/postgres-ops-integration.sh`. Set `MCP_IMAGE` to an immutable digest reference to include isolated MCP verification.

## Document storage and extraction contract

`extract_pdf_text` accepts a local PDF path and returns extracted text with optional page-level text. PDF content is treated as untrusted user data: the server returns the extracted content but does not persist it, execute it, or follow instructions embedded in the document. Invalid paths, non-PDF files, unreadable files, and extraction failures are returned as sanitized validation errors.

The tool rejects oversized PDFs before parsing and caps page count before extraction. Returned text is capped through `maxCharacters` to avoid oversized MCP responses. If `includePages` is omitted, page-level text is included by default; set it to `false` when only the joined text is needed.

`store_document_file` accepts a local file path and optional media type. When the media type is omitted it defaults to `application/pdf`; PDF storage validates the PDF header and enforces the same conservative file-size limit used by PDF extraction. Stored binary content is written once to PostgreSQL `document.blobs` as `bytea` and deduplicated by SHA-256, while upload/document metadata is stored separately in `document.documents`. The tool exposes back only metadata: UUID, original file name, media type, byte size, hash, and timestamps.

`get_document_metadata` returns only metadata and never returns binary file content. `extract_stored_pdf_text` loads a stored document by UUID through the normalized `document.documents -> document.blobs` relationship and uses the same bounded PDF reader path. When `persistExtraction` is `true`, the service persists one canonical extraction bounded by the application-wide 250,000-character maximum; each call applies its own `maxCharacters` only to the response view, so an initial low-limit request cannot constrain later larger requests. Persisted extraction is idempotent per stored document: `document.pdf_extractions.file_id` is unique and references `document.documents(id)`. Legacy non-canonical rows are refreshed in place without changing their extraction UUID. Page text is not duplicated in PostgreSQL; when `includePages` is true, both new and cached calls return deterministic request-bounded pages by reading the stored PDF, while `includePages=false` reuses the canonical text without reparsing. Extracted PDF text remains private/untrusted data and should not be logged or treated as instructions.

`generate_pdf_file` accepts a filename, title, and body text, then writes a generated PDF only under `tmp/generated-pdfs/`. The directory is intentionally runtime-only: `.gitignore` ignores generated PDFs while preserving `tmp/generated-pdfs/.gitkeep` so the directory exists in version control. Filenames are sanitized and forced to `.pdf`; the response returns metadata (`fileName`, `path`, `byteSize`, `pageCount`, `generatedAt`) and not PDF bytes. Generated PDFs use a white page background, identical header/footer chrome with right-aligned page numbers only, and thin chrome-colored separators before section headings. Pagination keeps section headings with their first content line and keeps resume entry headings with their date/first-detail lines when content lands near a page boundary.

`generate_pdf_resume` accepts a profile UUID, renders a master resume from the normalized profile schema, writes the runtime PDF under `tmp/generated-pdfs/master-resume/`, stores that generated file through the document storage slice, and upserts one current `profile.profile_resume_documents` link per profile/resume type. `generate_canadian_pdf_resume` reuses the same profile-loading, PDF-generation, document-storage, and profile-link workflow, but renders the Canadian variant under `tmp/generated-pdfs/canadian-resume/` with Canadian resume defaults: no photo/personal-demographic fields, professional contact links only, professional summary, grouped technical skills, reverse-chronological professional experience, education, and languages. The current Canadian variant intentionally omits projects so education remains visible and the resume stays concise. `generate_german_tailored_resume` requires `profileId` and `jobId`, builds structured Lebenslauf content tailored to the job (no North-American summary), translates EN→DE offline with an embedded glossary/phrase map, stores normalized sections/entries/bullets for both languages under one `resume.resumes` parent unique on `(profile_id, job_id, format=germany)`, writes both PDFs under `tmp/generated-pdfs/german-resume/`, and replaces previous EN/DE variants with orphan document/file cleanup. Optional profile personal details live in `profile.profile_personal_details` (DOB, nationality, optional photo document). Resume PDFs use a modern ATS-safe single-column layout with compact margins, chrome header/footer bars with white right-aligned page numbers only, an inline contact/link header, larger name/title treatment, grouped skills, indented bullets, and thin chrome separators before section headings. Responses return profile/document/link/generated-file metadata only; they do not return PDF bytes or rendered resume body text.

## Profile PDF ingestion contract

`ingest_profile_from_stored_pdf` orchestrates stored-document extraction, conservative profile-field extraction, normalized profile persistence, and provenance linking. It always extracts stored PDFs with `persistExtraction=true`, then creates or updates a profile through `ProfileService` so profile validation/canonicalization remains the single write gate.

The provenance chain is one-to-one:

```text
document.blobs.sha256 UNIQUE
  -> document.documents.blob_id
  -> document.pdf_extractions.file_id UNIQUE
  -> profile.profile_pdf_sources.pdf_extraction_id UNIQUE
  -> profile.profile_pdf_sources.profile_id UNIQUE
  -> profile.profiles + profile-owned child tables
```

If ingestion is rerun for the same stored PDF, the service returns the existing profile/source link and does not duplicate the file, extraction, profile, or profile-source rows. If the same PDF bytes are stored again as a distinct document row, ingestion resolves the prior source link by `document.blobs.sha256` and returns that existing profile/source link instead of creating another profile. For changed/re-exported PDFs that do not share the same SHA-256, ingestion checks strong extracted identity fields before creating a new profile: canonical email and normalized links. Strong matches return non-mutating result statuses (`DUPLICATE_PROFILE_CANDIDATE` or `AMBIGUOUS_PROFILE_CANDIDATES`) with the candidate profile ID, matched fields, and a recommended rerun action. Successful mutation/reuse paths also return explicit statuses: `CREATED_PROFILE`, `UPDATED_PROFILE`, or `REUSED_EXISTING_SOURCE`. The ingestion tool returns profile/document/extraction/source IDs and counts only; it does not return raw extracted resume text.

The deterministic fallback extractor is section-aware: it reads summary text from `Summary`/`Profile` sections, extracts technical skills only from recognized skills/technology sections, extracts human languages only from language sections, extracts pipe-delimited education/experience/project entries from their own sections, and normalizes obvious LinkedIn/GitHub links by stripping query strings, fragments, and trailing slashes. Structured extraction remains conservative and treats PDF text as untrusted data.

## Profile write contract

MCP success responses use object-shaped `structuredContent` so clients that reject raw top-level arrays can validate responses consistently. For example, `list_profiles` returns `{ "profiles": [...] }` rather than a bare array.

`create_profile` and `update_profile` validate profile write requests in the application layer before persistence. Invalid payloads are rejected with `validation_error` application exceptions and safe details containing only the invalid field path and reason. The profile MCP adapter maps application and unexpected failures to MCP `CallToolResult` errors with sanitized structured content instead of leaking raw exception text.

`search_profiles` is backed by a deterministic `ProfileSearchService` over the normalized profile aggregate. It tokenizes the query, searches profile identity fields and owned child collections (contacts, links, skills, languages, education, experience, projects, and project technologies), ranks matches by weighted evidence, and returns profile identities with score and matched field names only. Search loading is bounded: PostgreSQL loads matching profile aggregates in a fixed set of collection queries instead of per-profile lookups. Responses report both `totalMatches` and `returnedCount` so callers can distinguish full match count from the truncated page size. It does not return raw resume text, descriptions, or private document content.

After validation, profile writes are canonicalized before persistence: required text is trimmed, email and normalized keys are lower-cased, blank optional text becomes `null`, and `null` child collections become empty lists. PostgreSQL also enforces canonical uniqueness and future-write canonical form for profile email and normalized profile child keys through Flyway-managed indexes and `NOT VALID` check constraints. Persistence constraint failures are mapped to sanitized validation errors.

Currently validated examples include:

- non-blank `fullName` and email
- basic email shape
- required contact, link, skill, language, and project technology fields
- non-negative display order values
- education/experience date ranges
- duplicate normalized skills, languages, links, contacts, education entries, experiences, projects, and project technologies within one request

Database constraints still protect persistence integrity, but expected request problems should fail before PostgreSQL constraint errors.

## Job insertion contract

Job storage uses the Flyway-managed `job_schema` schema. Canonical job fields live in `job_schema.jobs`, required/normalized skills live in `job_schema.job_skills`, and method-specific provenance is separated by insertion path:

- `job_schema.job_text_ingestions` for pasted-text insertion metadata, keyed by a SHA-256 hash of canonical source text.
- `job_schema.job_link_ingestions` for URL insertion metadata, keyed by normalized URL and storing fetch status/title provenance.
- `job_schema.job_analysis_runs` for URL-analysis provenance: bounded structured input, persisted structured Hermes response, validation status/errors, response hashes, and the normalized job ID once an analysis is accepted.

Every stored job has exactly one provenance source, and it must match `job_schema.jobs.source_method`: text-backed jobs require exactly one `job_text_ingestions` row and no `job_link_ingestions` row, while link-backed jobs require the inverse. The invariant is enforced in the domain aggregate, in the PostgreSQL repository write path, and by Flyway migration `V14__enforce_job_source_provenance.sql`, after the V13 safe URL identity migration.

`add_job_from_text` and `add_job_from_link` are idempotent, but link identity is intentionally split into three forms. The full retrieval URL is ephemeral and used only for the outbound fetch. Persisted/MCP-visible `url` is a redacted safe display URL with userinfo, query, and fragment removed. Persisted `normalized_url` is the canonical link identity used for dedupe; it retains only constrained posting identifiers for recognized ATS hosts (`jk` on Indeed and `gh_jid` on Greenhouse). Tracking values such as `gh_src`, referral/session values, unrecognized-host query parameters, and malformed or oversized identifiers are always dropped. Link submissions first check `normalized_url`, then the canonical job fingerprint, which now includes the canonical link identity for link-based jobs so distinct ATS posting IDs do not collapse. Text submissions remain keyed by `input_text_hash` and then the content fingerprint. The direct link path is deliberately restricted to public IP-literal URLs; use pasted text for normal hostname-based job boards. The application layer derives a conservative title, required skills, and experience requirement from the supplied/fetched text when explicit fields are absent, but callers can override the structured fields when better data is available. `update_job` preserves omitted fields, recalculates the canonical fingerprint when title/company/location/description change, replaces skills only when a skills list is provided, and preserves insertion provenance. `delete_job` removes the canonical row and cascades owned skill/provenance rows through the Flyway-managed schema.

The staged Hermes URL flow is split intentionally. `analyze_job_link` validates a URL, fetches bounded page content using the caller-supplied URL, persists only the safe display URL plus canonical link identity, sends only those redacted forms to the provider, stores the structured Hermes response in `job_schema.job_analysis_runs`, and returns an analysis-run report without writing a normalized job. `add_job_from_analysis` then loads that stored analysis row, validates/canonicalizes the persisted response in Java, rejects weak URL-only or JavaScript-shell analyses, and only then creates or reuses a normalized job. Until a live Hermes CLI adapter is configured, the default analysis provider records a safe failed analysis instead of pretending enrichment succeeded.

Job search is deterministic and ATS/matching-ready: it tokenizes the query, ranks title/company/location/description/experience/employment/seniority/skills by weighted evidence, and returns matched field names with a score. Search loading is bounded: PostgreSQL loads job aggregates in a fixed set of queries instead of per-job lookups, and responses report both `totalMatches` and `returnedCount` so callers can distinguish full match count from the current page. The score is internal search metadata, not applicant-facing resume content.

## Verification

Run focused unit tests while working on profile or document behavior:

```bash
./mvnw -q -Dtest=ProfileWriteValidatorTests,ProfileWriteCanonicalizerTests,ProfileServiceTests,ProfileMcpAdapterTests test
./mvnw -q -Dtest=PdfTextExtractionServiceTests,DocumentStorageServiceTests,PdfGenerationServiceTests,GeneratePdfResumeServiceTests,DocumentMcpAdapterTests,DocumentPdfGenerateResumeMcpTests test
./mvnw -q -Dtest=ProfilePdfIngestionServiceTests,ProfilePdfIngestionMcpAdapterTests test
./mvnw -q -Dtest=JobServiceTests,JobAnalysisServiceTests,JobMcpAdapterTests,HttpJobLinkContentFetcherTests test
./mvnw -q -Dtest=PostgresProfileSearchIntegrationTests,PostgresJobSearchIntegrationTests,PostgresJobRepositoryIntegrationTests test
```

Run the unit test suite before claiming non-database completion:

```bash
./mvnw test
```

Run Docker-backed PostgreSQL integration tests and the JaCoCo coverage gate explicitly when Docker/Testcontainers is available:

```bash
./mvnw -Pintegration-tests verify
```

The GitHub Actions pipeline runs fast validation and unit tests on pull requests to `development`. Feature/fix pull requests targeting `master` additionally run the Docker-backed integration/coverage gate, containerized MCP STDIO smoke, and Qodana. A `development` → `master` promotion PR is a special case whose candidate tree must match `origin/development` exactly; the same promotion guard runs on direct pushes to `master` as defense in depth. Trusted pushes to `development` and manual CI runs also retain the heavy integration and container smoke gates. Tag releases remain explicit: pushing a `v*` tag runs clean full verification (`./mvnw clean -Drevision=<version> -Pintegration-tests verify`), copies the verified Maven jar to a versioned release artifact, builds and smoke-tests `linux/amd64` and `linux/arm64` release images from that same verified jar, records and transfers each exact smoke-tested platform image, and generates CycloneDX and SPDX SBOMs for the jar and each image. The publishing job verifies each loaded image ID and the corresponding registry config digest before composing a GHCR multi-platform manifest list. Stable releases also publish `:latest`; the workflow verifies both required platforms, signs and attests the immutable manifest-list digest with keyless Cosign, then uploads the jar, checksum, per-platform SBOMs, image IDs/digests, and verification reports as release artifacts. Docker Desktop automatically selects the matching Linux architecture on macOS and Windows; the project does not publish `darwin/*` or Windows-container variants. Manual release workflow runs always perform dry-run verification and never publish without a `v*` tag.

Container inputs are digest-pinned in `Dockerfile`, `Dockerfile.release`, and `compose.yaml`. `.github/dependabot.yml` tracks both GitHub Actions and Docker references weekly so pinned bases and pinned workflow actions can be refreshed intentionally instead of drifting silently.

To publish a release explicitly, run the release helper with the desired version tag:

```bash
./scripts/release.sh v0.1.0
```

The helper requires a clean working tree, fetches `origin/master`, refuses existing local or remote tags, creates an annotated `vMAJOR.MINOR.PATCH[-PRERELEASE]` tag that points exactly at `origin/master`, and pushes only that tag. The tag push triggers `.github/workflows/release.yml`, which performs verification before publishing the GitHub Release and GHCR image.

To exercise the release flow safely without publishing anything, run `.github/workflows/release.yml` manually from a branch or `development`. The workflow dispatch path performs clean Maven verification, verified-jar packaging, builds and smoke-tests both Linux release architectures, and generates jar plus per-platform image SBOMs. It also exercises keyless Cosign signing and SLSA provenance attestation against `SHA256SUMS`, then verifies both with the expected GitHub Actions OIDC workflow identity. It never creates a GitHub Release or publishes a GHCR package. Download the dry-run artifacts and verify the JAR checksum locally with:

```bash
sha256sum -c SHA256SUMS
```

The Maven Wrapper now records `distributionSha256Sum` for the pinned Apache Maven 3.9.16 distribution. When updating `.mvn/wrapper/maven-wrapper.properties`, download the exact replacement archive from Apache, verify its upstream checksum/signature, compute its SHA-256 locally, and update both `distributionUrl` and `distributionSha256Sum` together.

## License

`job-engine-spring` is licensed under the [Apache License 2.0](LICENSE). Third-party dependencies remain subject to their respective licenses.
