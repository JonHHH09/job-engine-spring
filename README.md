# job-engine-spring

`job-engine-spring` is a Spring Boot / Spring AI MCP server that is replacing the legacy Python Job-Engine runtime with a Java/Spring implementation.

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
    web-application-type: none
  datasource:
    url: ${JOB_ENGINE_POSTGRES_URL:jdbc:postgresql://localhost:5432/job_engine}
    username: ${JOB_ENGINE_POSTGRES_USER:}
    password: ${JOB_ENGINE_POSTGRES_PASSWORD:}
job-engine:
  document:
    import-root: ${JOB_ENGINE_DOCUMENT_IMPORT_ROOT:tmp/imports}
```

Never commit real credentials, private resume data, API keys, production connection details, or machine-local absolute paths. Configuration paths must not be hardcoded; use environment placeholders, documented safe relative defaults, or caller-supplied runtime settings.

MCP is local-only. The server is configured as an STDIO subprocess (`spring.ai.mcp.server.stdio=true`) with `spring.main.web-application-type=none`, and `McpLocalOnlyStartupGuard` fails startup if either local-only invariant is changed. Tool schemas do not carry per-call access tokens; the security boundary is the absence of any network listener plus the local OS/process boundary of the MCP client that launches the jar. Local file imports for PDF extraction/storage are restricted to `job-engine.document.import-root` by default; generated resume PDFs bypass that import-root check because they are produced internally under `tmp/generated-pdfs/`. Job URL fetching is SSRF-hardened: redirects are not followed, local/private/metadata/userinfo targets are rejected before send, and only public IP-literal hosts are accepted so the validated address is the address used by the connection. Hostname allow-list configuration was intentionally removed because resolving a hostname before `HttpClient` connects does not prevent DNS-rebinding/TOCTOU attacks.

For STDIO MCP, keep banner/log output off stdout so JSON-RPC messages are not polluted.

## Local STDIO MCP deployment

The default local deployment is a packaged Spring Boot jar launched by an MCP client over STDIO. The application is not a standalone HTTP daemon in this mode: Hermes or another MCP client starts `java -jar ...`, keeps stdin/stdout open, and discovers tools from that live subprocess.

Build and verify the local jar with:

```bash
./scripts/rebuild-local-mcp-jar.sh
```

The script runs `./mvnw test`, packages `target/job-engine-spring-0.0.1-SNAPSHOT.jar`, and then runs `hermes mcp test job-engine-spring` when the Hermes CLI is available. To skip unit tests after they have already passed, run:

```bash
RUN_TESTS=false ./scripts/rebuild-local-mcp-jar.sh
```

Rebuilding the jar updates the file on disk only. Any already-running Hermes MCP connection keeps using the old Java process until it reconnects. After rebuilding, run `/reload-mcp` in the active Hermes session. If tool names, argument schemas, prompts, or resources changed, start a fresh Hermes session with `/reset` after reloading so the tool schema in the agent context is current.

`scripts/restart-local-mcp-server.sh` is kept as a local maintenance helper because it stops stale matching local jar subprocesses, rebuilds the jar without recursively running the MCP smoke test, and optionally runs the server in foreground STDIO mode. It remains local-only and does not expose an HTTP daemon.

## Local containerized MCP deployment

The local container deployment keeps the same STDIO MCP contract: the MCP server still has no published HTTP port, and the MCP client talks to the container through Docker stdin/stdout. `compose.yaml` starts a private PostgreSQL service with no host port binding, and `scripts/run-local-mcp-container.sh` starts the database, removes any stale local MCP STDIO container for this project, joins the MCP container to the Compose network, and then execs the MCP server container with clean stdout for JSON-RPC.

Build the local image when application code changes:

```bash
docker compose build mcp
```

Run the containerized MCP server over STDIO:

```bash
./scripts/run-local-mcp-container.sh
```

For Hermes, configure the MCP command to invoke the script above instead of `java -jar ...`. The script writes Docker lifecycle output to stderr and reserves stdout for the MCP JSON-RPC transport. Use `MCP_CONTAINER_BUILD=always ./scripts/run-local-mcp-container.sh` when you want the script to rebuild the image before launching, or keep the default image-missing-only behavior for faster MCP client startup.

The script gives the MCP subprocess container a stable name, `job-engine-spring-mcp-stdio` by default, and removes stale containers for that selected name before launching a new one. The default instance also cleans up older project MCP containers from pre-single-instance runs. Override the name with `MCP_CONTAINER_NAME=...` only when you intentionally need an isolated local MCP instance; custom-name cleanup is scoped to that instance so parallel isolated sessions do not kill each other. The Compose-managed `mcp` service is behind the `manual-mcp` profile, so plain `docker compose up -d` starts only the default services such as PostgreSQL; use `docker compose --profile manual-mcp up mcp` only for direct Compose debugging.

Host-visible local file imports are mounted from `tmp/imports/` into the container as read-only files, and generated PDFs are mounted through `tmp/generated-pdfs/`. Do not publish the MCP container or database ports unless the local-only architecture is intentionally changed.

Smoke-test the containerized MCP transport with:

```bash
MCP_CONTAINER_BUILD=always python3 scripts/smoke-mcp-stdio.py -- ./scripts/run-local-mcp-container.sh
```

Stop the local database and remove its development volume when you intentionally want a clean local container database:

```bash
docker compose down -v
```

## Document storage and extraction contract

`extract_pdf_text` accepts a local PDF path and returns extracted text with optional page-level text. PDF content is treated as untrusted user data: the server returns the extracted content but does not persist it, execute it, or follow instructions embedded in the document. Invalid paths, non-PDF files, unreadable files, and extraction failures are returned as sanitized validation errors.

The tool rejects oversized PDFs before parsing and caps page count before extraction. Returned text is capped through `maxCharacters` to avoid oversized MCP responses. If `includePages` is omitted, page-level text is included by default; set it to `false` when only the joined text is needed.

`store_document_file` accepts a local file path and optional media type. When the media type is omitted it defaults to `application/pdf`; PDF storage validates the PDF header and enforces the same conservative file-size limit used by PDF extraction. Stored binary content is written once to PostgreSQL `document.blobs` as `bytea` and deduplicated by SHA-256, while upload/document metadata is stored separately in `document.documents`. The tool exposes back only metadata: UUID, original file name, media type, byte size, hash, and timestamps.

`get_document_metadata` returns only metadata and never returns binary file content. `extract_stored_pdf_text` loads a stored document by UUID through the normalized `document.documents -> document.blobs` relationship, extracts bounded text using the same PDF reader path, and persists the returned bounded extraction text to `document.pdf_extractions` only when `persistExtraction` is `true`. Persisted extraction is idempotent per stored document: `document.pdf_extractions.file_id` is unique and references `document.documents(id)`, so repeated persisted extraction calls for the same stored document return/reuse the existing canonical extraction row instead of creating duplicates. Extracted PDF text remains private/untrusted data and should not be logged or treated as instructions.

`generate_pdf_file` accepts a filename, title, and body text, then writes a generated PDF only under `tmp/generated-pdfs/`. The directory is intentionally runtime-only: `.gitignore` ignores generated PDFs while preserving `tmp/generated-pdfs/.gitkeep` so the directory exists in version control. Filenames are sanitized and forced to `.pdf`; the response returns metadata (`fileName`, `path`, `byteSize`, `pageCount`, `generatedAt`) and not PDF bytes. Generated PDFs use a white page background, identical header/footer chrome with right-aligned page numbers only, and thin chrome-colored separators before section headings. Pagination keeps section headings with their first content line and keeps resume entry headings with their date/first-detail lines when content lands near a page boundary.

`generate_pdf_resume` accepts a profile UUID, renders a master resume from the normalized profile schema, writes the runtime PDF under `tmp/generated-pdfs/master-resume/`, stores that generated file through the document storage slice, and upserts one current `profile.profile_resume_documents` link per profile/resume type. `generate_canadian_pdf_resume` reuses the same profile-loading, PDF-generation, document-storage, and profile-link workflow, but renders the Canadian variant under `tmp/generated-pdfs/canadian-resume/` with Canadian resume defaults: no photo/personal-demographic fields, professional contact links only, professional summary, grouped technical skills, reverse-chronological professional experience, education, and languages. The current Canadian variant intentionally omits projects so education remains visible and the resume stays concise. Resume PDFs use a modern ATS-safe single-column layout with compact margins, chrome header/footer bars with white right-aligned page numbers only, an inline contact/link header, larger name/title treatment, grouped skills, indented bullets, and thin chrome separators before section headings. Responses return profile/document/link/generated-file metadata only; they do not return PDF bytes or rendered resume body text.

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

`search_profiles` is backed by a deterministic `ProfileSearchService` over the normalized profile aggregate. It tokenizes the query, searches profile identity fields and owned child collections (contacts, links, skills, languages, education, experience, projects, and project technologies), ranks matches by weighted evidence, and returns profile identities with score and matched field names only. It does not return raw resume text, descriptions, or private document content.

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

`add_job_from_text` and `add_job_from_link` are idempotent, but link identity is intentionally split into three forms. The full retrieval URL is ephemeral and used only for the outbound fetch. Persisted/MCP-visible `url` is a redacted safe display URL with userinfo, query, and fragment removed. Persisted `normalized_url` is the canonical link identity used for dedupe; it keeps only an explicit allowlist of posting-identity parameters such as `jk`, `gh_jid`, `jobId`, `job_id`, `posting_id`, `reqId`, `req_id`, and `vacancyId`, while discarding referral, session, invitation, and signed-query values. Link submissions first check `normalized_url`, then the canonical job fingerprint, which now includes the canonical link identity for link-based jobs so distinct ATS posting IDs do not collapse. Text submissions remain keyed by `input_text_hash` and then the content fingerprint. The direct link path is deliberately restricted to public IP-literal URLs; use pasted text for normal hostname-based job boards. The application layer derives a conservative title, required skills, and experience requirement from the supplied/fetched text when explicit fields are absent, but callers can override the structured fields when better data is available. `update_job` preserves omitted fields, recalculates the canonical fingerprint when title/company/location/description change, replaces skills only when a skills list is provided, and preserves insertion provenance. `delete_job` removes the canonical row and cascades owned skill/provenance rows through the Flyway-managed schema.

The staged Hermes URL flow is split intentionally. `analyze_job_link` validates a URL, fetches bounded page content using the caller-supplied URL, persists only the safe display URL plus canonical link identity, sends only those redacted forms to the provider, stores the structured Hermes response in `job_schema.job_analysis_runs`, and returns an analysis-run report without writing a normalized job. `add_job_from_analysis` then loads that stored analysis row, validates/canonicalizes the persisted response in Java, rejects weak URL-only or JavaScript-shell analyses, and only then creates or reuses a normalized job. Until a live Hermes CLI adapter is configured, the default analysis provider records a safe failed analysis instead of pretending enrichment succeeded.

Job search is deterministic and ATS/matching-ready: it tokenizes the query, ranks title/company/location/description/experience/employment/seniority/skills by weighted evidence, and returns matched field names with a score. The score is internal search metadata, not applicant-facing resume content.

## Verification

Run focused unit tests while working on profile or document behavior:

```bash
./mvnw -q -Dtest=ProfileWriteValidatorTests,ProfileWriteCanonicalizerTests,ProfileServiceTests,ProfileMcpAdapterTests test
./mvnw -q -Dtest=PdfTextExtractionServiceTests,DocumentStorageServiceTests,PdfGenerationServiceTests,GeneratePdfResumeServiceTests,DocumentMcpAdapterTests,DocumentPdfGenerateResumeMcpTests test
./mvnw -q -Dtest=ProfilePdfIngestionServiceTests,ProfilePdfIngestionMcpAdapterTests test
./mvnw -q -Dtest=JobServiceTests,JobAnalysisServiceTests,JobMcpAdapterTests,HttpJobLinkContentFetcherTests test
```

Run the unit test suite before claiming non-database completion:

```bash
./mvnw test
```

Run Docker-backed PostgreSQL integration tests and the JaCoCo coverage gate explicitly when Docker/Testcontainers is available:

```bash
./mvnw -Pintegration-tests verify
```

The GitHub Actions pipeline runs fast validation and unit tests on pull requests to `development`. Feature/fix pull requests targeting `master` additionally run the Docker-backed integration/coverage gate, containerized MCP STDIO smoke, and Qodana. A `development` → `master` promotion PR is a special case whose candidate tree must match `origin/development` exactly. Trusted pushes to `development` and manual CI runs also retain the heavy integration and container smoke gates. Tag releases remain explicit: pushing a `v*` tag runs clean full verification (`./mvnw clean -Drevision=<version> -Pintegration-tests verify`), copies the verified Maven jar to a versioned release artifact, builds and smoke-tests the release container once, transfers that exact image to the publish job, verifies its registry config digest against the smoke-tested image ID, publishes `ghcr.io/<owner>/job-engine-spring:<tag>` and `:latest` for stable tags, and uploads the jar, checksum, image ID, image digest, and verification reports as release artifacts. Manual release workflow runs always perform dry-run verification and never publish without a `v*` tag.

To publish a release explicitly, run the release helper with the desired version tag:

```bash
./scripts/release.sh v0.1.0
```

The helper requires a clean working tree, fetches `origin/master`, refuses existing local or remote tags, creates an annotated `vMAJOR.MINOR.PATCH[-PRERELEASE]` tag that points exactly at `origin/master`, and pushes only that tag. The tag push triggers `.github/workflows/release.yml`, which performs verification before publishing the GitHub Release and GHCR image.
