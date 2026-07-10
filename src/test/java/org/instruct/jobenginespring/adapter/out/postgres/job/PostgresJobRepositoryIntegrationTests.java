package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobAnalysisRun;
import org.instruct.jobenginespring.domain.job.JobLinkIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresJobRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-06T21:15:00Z");
    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SKILL_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;

    private PostgresJobRepository repository;
    private PostgresJobAnalysisRunRepository analysisRunRepository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE job_schema.jobs CASCADE");
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        repository = new PostgresJobRepository(namedJdbc);
        analysisRunRepository = new PostgresJobAnalysisRunRepository(JdbcClient.create(namedJdbc));
    }

    @Test
    void flywayCreatesJobSchemaTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'job_schema'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);

        assertEquals(List.of(
                "job_analysis_runs",
                "job_link_ingestions",
                "job_skills",
                "job_text_ingestions",
                "jobs"
        ), tables);
    }

    @Test
    void savesAndFindsTextJobAggregate() {
        JobAggregate saved = repository.saveJobAggregate(textAggregate("fingerprint-text", "hash-text"));

        Optional<JobAggregate> found = repository.findJobAggregate(saved.job().id());

        assertTrue(found.isPresent());
        JobAggregate aggregate = found.orElseThrow();
        assertEquals("Java Developer", aggregate.job().title());
        assertEquals("java", aggregate.skills().getFirst().normalizedSkill());
        assertEquals("manual paste", aggregate.textIngestion().sourceLabel());
        assertTrue(repository.findByCanonicalFingerprint("fingerprint-text").isPresent());
        assertTrue(repository.findByInputTextHash("hash-text").isPresent());
        assertEquals(List.of("Java Developer"), repository.listJobs().stream().map(JobPosting::title).toList());
    }

    @Test
    void savesAndFindsLinkJobAggregateByNormalizedUrl() {
        JobAggregate aggregate = new JobAggregate(
                posting(JOB_ID, "link", "fingerprint-link"),
                List.of(new JobSkill(SKILL_ID, JOB_ID, "Kubernetes", "kubernetes", true, 0, NOW)),
                new JobLinkIngestion(
                        UUID.fromString("12121212-1212-1212-1212-121212121212"),
                        JOB_ID,
                        "https://example.test/jobs/1",
                        "https://example.test/jobs/1?jk=job-1",
                        NOW,
                        200,
                        "Platform Engineer",
                        NOW
                ),
                null
        );

        JobAggregate saved = repository.saveJobAggregate(aggregate);

        Optional<JobAggregate> found = repository.findByNormalizedSourceUrl("https://example.test/jobs/1?jk=job-1");
        assertTrue(found.isPresent());
        assertEquals(saved.job().id(), found.orElseThrow().job().id());
        assertEquals("https://example.test/jobs/1?jk=job-1", saved.linkIngestion().normalizedUrl());
    }

    @Test
    void savesFindsAndUpdatesJobAnalysisRunJson() {
        UUID analysisRunId = UUID.fromString("22222222-2222-3333-4444-555555555555");
        JobAnalysisRun saved = analysisRunRepository.save(new JobAnalysisRun(
                analysisRunId,
                "link",
                "https://example.test/jobs/1",
                "https://example.test/jobs/1?jk=job-1",
                "FETCHED",
                200,
                "Platform Engineer",
                "input-hash",
                Map.of("sourceMethod", "link", "boundedVisibleText", "Fetched job text"),
                "SUCCEEDED",
                Map.of("title", "Platform Engineer", "description", "Build internal developer platforms for product teams."),
                "response-hash",
                "VALID",
                List.of(),
                null,
                NOW,
                NOW
        ));

        Optional<JobAnalysisRun> found = analysisRunRepository.findById(analysisRunId);

        assertTrue(found.isPresent());
        assertEquals(saved.id(), found.orElseThrow().id());
        assertEquals("Fetched job text", saved.inputJson().get("boundedVisibleText"));
        assertEquals("Platform Engineer", saved.hermesResponseJson().get("title"));

        repository.saveJobAggregate(new JobAggregate(
                posting(JOB_ID, "link", "fingerprint-for-analysis"),
                List.of(),
                new JobLinkIngestion(
                        UUID.fromString("23232323-2323-2323-2323-232323232323"),
                        JOB_ID,
                        "https://example.test/jobs/1",
                        "https://example.test/jobs/1?jk=job-1",
                        NOW,
                        200,
                        "Platform Engineer",
                        NOW
                ),
                null
        ));
        JobAnalysisRun updated = analysisRunRepository.update(new JobAnalysisRun(
                saved.id(),
                saved.sourceType(),
                saved.originalUrl(),
                saved.normalizedUrl(),
                saved.fetchStatus(),
                saved.httpStatus(),
                saved.fetchedTitle(),
                saved.inputSha256(),
                saved.inputJson(),
                saved.hermesStatus(),
                saved.hermesResponseJson(),
                saved.hermesResponseSha256(),
                "VALID",
                List.of(),
                JOB_ID,
                saved.createdAt(),
                NOW.plusSeconds(60)
        ));

        assertEquals(JOB_ID, updated.createdJobId());
        assertEquals(NOW.plusSeconds(60), updated.updatedAt());
    }

    @Test
    void savesJobWithoutSkillsOrPostedAt() {
        JobPosting posting = new JobPosting(
                UUID.fromString("33333333-2222-3333-4444-555555555555"),
                "text",
                null,
                "Plain Job",
                null,
                null,
                "Plain description",
                null,
                null,
                null,
                null,
                "fingerprint-no-skills",
                NOW,
                NOW
        );

        JobAggregate saved = repository.saveJobAggregate(new JobAggregate(posting, List.of(), null, null));

        assertEquals(List.of(), saved.skills());
        assertEquals(null, saved.job().postedAt());
    }

    @Test
    void saveJobAggregateReusesExistingJobForDuplicateCanonicalFingerprint() {
        repository.saveJobAggregate(textAggregate("duplicate-fingerprint", "hash-one"));

        JobAggregate duplicateFingerprint = textAggregate(
                UUID.fromString("99999999-2222-3333-4444-555555555555"),
                UUID.fromString("88888888-2222-3333-4444-555555555555"),
                "duplicate-fingerprint",
                "hash-two"
        );

        JobAggregate reused = repository.saveJobAggregate(duplicateFingerprint);

        assertEquals(JOB_ID, reused.job().id());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM job_schema.jobs", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_text_ingestions", Integer.class));
    }

    @Test
    void saveJobAggregateReusesExistingJobForDuplicateInputTextHash() {
        repository.saveJobAggregate(textAggregate("fingerprint-one", "duplicate-hash"));

        JobAggregate duplicateHash = textAggregate(
                UUID.fromString("99999999-2222-3333-4444-555555555555"),
                UUID.fromString("88888888-2222-3333-4444-555555555555"),
                "fingerprint-two",
                "duplicate-hash"
        );

        JobAggregate reused = repository.saveJobAggregate(duplicateHash);

        assertEquals(JOB_ID, reused.job().id());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM job_schema.jobs", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_text_ingestions", Integer.class));
        assertTrue(repository.findByInputTextHash("duplicate-hash").isPresent());
    }

    @Test
    void saveJobAggregateReusesExistingJobForDuplicateNormalizedUrl() {
        repository.saveJobAggregate(linkAggregate(
                JOB_ID,
                UUID.fromString("12121212-1212-1212-1212-121212121212"),
                "fingerprint-one",
                "https://example.test/jobs/1?jk=job-1"
        ));

        JobAggregate duplicateUrl = linkAggregate(
                UUID.fromString("99999999-2222-3333-4444-555555555555"),
                UUID.fromString("88888888-2222-3333-4444-555555555555"),
                "fingerprint-two",
                "https://example.test/jobs/1?jk=job-1"
        );

        JobAggregate reused = repository.saveJobAggregate(duplicateUrl);

        assertEquals(JOB_ID, reused.job().id());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM job_schema.jobs", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_link_ingestions", Integer.class));
        assertTrue(repository.findByNormalizedSourceUrl("https://example.test/jobs/1?jk=job-1").isPresent());
    }

    @Test
    void saveJobAggregateDoesNotReuseExistingJobForDistinctCanonicalIdentityQueryParameters() {
        repository.saveJobAggregate(linkAggregate(
                JOB_ID,
                UUID.fromString("12121212-1212-1212-1212-121212121212"),
                "fingerprint-one",
                "https://example.test/jobs/1?jk=job-1"
        ));

        JobAggregate distinctIdentity = linkAggregate(
                UUID.fromString("99999999-2222-3333-4444-555555555555"),
                UUID.fromString("88888888-2222-3333-4444-555555555555"),
                "fingerprint-two",
                "https://example.test/jobs/1?jk=job-2"
        );

        JobAggregate saved = repository.saveJobAggregate(distinctIdentity);

        assertEquals(distinctIdentity.job().id(), saved.job().id());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM job_schema.jobs", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_link_ingestions", Integer.class));
    }

    @Test
    void updatesJobAggregateAndReplacesPersistedSkills() {
        repository.saveJobAggregate(textAggregate("fingerprint-before-update", "hash-before-update"));
        JobPosting updatedPosting = new JobPosting(
                JOB_ID,
                "text",
                "Updated Source",
                "Senior Java Developer",
                "Updated Corp",
                "Montreal",
                "Build platform services",
                "5+ years",
                "Contract",
                "Senior",
                NOW.plusSeconds(30),
                "fingerprint-after-update",
                NOW,
                NOW.plusSeconds(60)
        );
        JobAggregate updated = repository.updateJobAggregate(new JobAggregate(
                updatedPosting,
                List.of(
                        new JobSkill(UUID.fromString("bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"), JOB_ID, "Kubernetes", "kubernetes", true, 0, NOW.plusSeconds(60)),
                        new JobSkill(UUID.fromString("cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee"), JOB_ID, "Java", "java", true, 1, NOW.plusSeconds(60))
                ),
                null,
                new JobTextIngestion(
                        UUID.fromString("66666666-2222-3333-4444-555555555555"),
                        JOB_ID,
                        "manual paste",
                        "hash-before-update",
                        NOW
                )
        ));

        JobAggregate found = repository.findJobAggregate(JOB_ID).orElseThrow();

        assertEquals("Senior Java Developer", updated.job().title());
        assertEquals("Updated Source", found.job().sourceLabel());
        assertEquals("Updated Corp", found.job().company());
        assertEquals("Montreal", found.job().location());
        assertEquals("Build platform services", found.job().description());
        assertEquals("5+ years", found.job().experienceRequirement());
        assertEquals("Contract", found.job().employmentType());
        assertEquals("Senior", found.job().seniority());
        assertEquals(NOW.plusSeconds(30), found.job().postedAt());
        assertEquals(NOW.plusSeconds(60), found.job().updatedAt());
        assertEquals(List.of("kubernetes", "java"), found.skills().stream().map(JobSkill::normalizedSkill).toList());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_skills WHERE job_id = ?", Integer.class, JOB_ID));
    }

    @Test
    void updateJobAggregateFailsWhenJobRowIsMissing() {
        UUID missingJobId = UUID.fromString("44444444-2222-3333-4444-555555555555");
        JobAggregate missingAggregate = new JobAggregate(
                posting(missingJobId, "text", "fingerprint-missing-update"),
                List.of(),
                null,
                null
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository.updateJobAggregate(missingAggregate)
        );

        assertEquals("Job disappeared during update: " + missingJobId, exception.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_skills", Integer.class));
    }

    @Test
    void deletesJobAggregateAndReturnsFalseWhenMissing() {
        repository.saveJobAggregate(linkAggregate(
                JOB_ID,
                UUID.fromString("12121212-1212-1212-1212-121212121212"),
                "fingerprint-delete",
                "https://example.test/jobs/delete"
        ));

        assertTrue(repository.deleteJob(JOB_ID));

        assertEquals(Optional.empty(), repository.findJobAggregate(JOB_ID));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM job_schema.jobs", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_skills", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM job_schema.job_link_ingestions", Integer.class));
        assertFalse(repository.deleteJob(JOB_ID));
    }

    private static JobAggregate textAggregate(String fingerprint, String textHash) {
        return textAggregate(JOB_ID, UUID.fromString("66666666-2222-3333-4444-555555555555"), fingerprint, textHash);
    }

    private static JobAggregate textAggregate(UUID jobId, UUID textIngestionId, String fingerprint, String textHash) {
        return new JobAggregate(
                posting(jobId, "text", fingerprint),
                List.of(new JobSkill(UUID.randomUUID(), jobId, "Java", "java", true, 0, NOW)),
                null,
                new JobTextIngestion(
                        textIngestionId,
                        jobId,
                        "manual paste",
                        textHash,
                        NOW
                )
        );
    }

    private static JobAggregate linkAggregate(UUID jobId, UUID linkIngestionId, String fingerprint, String normalizedUrl) {
        return new JobAggregate(
                posting(jobId, "link", fingerprint),
                List.of(new JobSkill(UUID.randomUUID(), jobId, "Kubernetes", "kubernetes", true, 0, NOW)),
                new JobLinkIngestion(
                        linkIngestionId,
                        jobId,
                        "https://example.test/jobs/1",
                        normalizedUrl,
                        NOW,
                        200,
                        "Platform Engineer",
                        NOW
                ),
                null
        );
    }

    private static JobPosting posting(UUID jobId, String sourceMethod, String fingerprint) {
        return new JobPosting(
                jobId,
                sourceMethod,
                "Example ATS",
                "Java Developer",
                "Example Corp",
                "Remote",
                "Build backend services",
                "3+ years",
                "Full-time",
                "Mid",
                NOW,
                fingerprint,
                NOW,
                NOW
        );
    }
}
