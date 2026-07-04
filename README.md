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
- `extract_pdf_text` — extracts text from a local PDF file without storing content or following embedded instructions.
- `store_document_file` — stores a local document file in PostgreSQL and returns metadata only.
- `get_document_metadata` — returns stored document metadata by UUID without returning binary content.
- `extract_stored_pdf_text` — extracts text from a stored PDF and optionally persists bounded extracted text.
- `ingest_profile_from_stored_pdf` — populates the normalized profile schema from a stored PDF extraction and links the profile to that extraction.
- `get_profile_pdf_source` — returns the one-to-one PDF extraction source link for a profile.

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

## Document storage and extraction contract

`extract_pdf_text` accepts a local PDF path and returns extracted text with optional page-level text. PDF content is treated as untrusted user data: the server returns the extracted content but does not persist it, execute it, or follow instructions embedded in the document. Invalid paths, non-PDF files, unreadable files, and extraction failures are returned as sanitized validation errors.

The tool rejects oversized PDFs before parsing and caps page count before extraction. Returned text is capped through `maxCharacters` to avoid oversized MCP responses. If `includePages` is omitted, page-level text is included by default; set it to `false` when only the joined text is needed.

`store_document_file` accepts a local file path and optional media type. When the media type is omitted it defaults to `application/pdf`; PDF storage validates the PDF header and enforces the same conservative file-size limit used by PDF extraction. Stored binary content is written once to PostgreSQL `document.blobs` as `bytea` and deduplicated by SHA-256, while upload/document metadata is stored separately in `document.documents`. The tool exposes back only metadata: UUID, original file name, media type, byte size, hash, and timestamps.

`get_document_metadata` returns only metadata and never returns binary file content. `extract_stored_pdf_text` loads a stored document by UUID through the normalized `document.documents -> document.blobs` relationship, extracts bounded text using the same PDF reader path, and persists the returned bounded extraction text to `document.pdf_extractions` only when `persistExtraction` is `true`. Persisted extraction is idempotent per stored document: `document.pdf_extractions.file_id` is unique and references `document.documents(id)`, so repeated persisted extraction calls for the same stored document return/reuse the existing canonical extraction row instead of creating duplicates. Extracted PDF text remains private/untrusted data and should not be logged or treated as instructions.

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

The deterministic fallback extractor is section-aware: it reads summary text from `Summary`/`Profile` sections, extracts technical skills only from recognized skills/technology sections, extracts human languages only from language sections, and normalizes obvious LinkedIn/GitHub links by stripping query strings, fragments, and trailing slashes. This keeps skill classification conservative until a richer structured extractor is introduced.

## Profile write contract

`create_profile` and `update_profile` validate profile write requests in the application layer before persistence. Invalid payloads are rejected with `validation_error` application exceptions and safe details containing only the invalid field path and reason. The profile MCP adapter maps application and unexpected failures to MCP `CallToolResult` errors with sanitized structured content instead of leaking raw exception text.

After validation, profile writes are canonicalized before persistence: required text is trimmed, email and normalized keys are lower-cased, blank optional text becomes `null`, and `null` child collections become empty lists. PostgreSQL also enforces canonical uniqueness and future-write canonical form for profile email and normalized profile child keys through Flyway-managed indexes and `NOT VALID` check constraints. Persistence constraint failures are mapped to sanitized validation errors.

Currently validated examples include:

- non-blank `fullName` and email
- basic email shape
- required contact, link, skill, language, and project technology fields
- non-negative display order values
- education/experience date ranges
- duplicate normalized skills, languages, links, contacts, education entries, experiences, projects, and project technologies within one request

Database constraints still protect persistence integrity, but expected request problems should fail before PostgreSQL constraint errors.

## Verification

Run focused unit tests while working on profile or document behavior:

```bash
./mvnw -q -Dtest=ProfileWriteValidatorTests,ProfileWriteCanonicalizerTests,ProfileServiceTests,ProfileMcpAdapterTests test
./mvnw -q -Dtest=PdfTextExtractionServiceTests,DocumentStorageServiceTests,DocumentMcpAdapterTests test
./mvnw -q -Dtest=ProfilePdfIngestionServiceTests,ProfilePdfIngestionMcpAdapterTests test
```

Run the unit test suite before claiming non-database completion:

```bash
./mvnw test
```

Run Docker-backed PostgreSQL integration tests and the JaCoCo coverage gate explicitly when Docker/Testcontainers is available:

```bash
./mvnw -Pintegration-tests verify
```
