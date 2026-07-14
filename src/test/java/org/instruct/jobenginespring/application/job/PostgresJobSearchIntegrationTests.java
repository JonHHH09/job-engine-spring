package org.instruct.jobenginespring.application.job;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.job.PostgresJobRepository;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobLinkIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.support.CountingDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresJobSearchIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-09T14:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static CountingDataSource dataSource;
    private static JdbcTemplate jdbc;

    private JobService service;
    private PostgresJobRepository repository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema")
                .load()
                .migrate();

        dataSource = new CountingDataSource(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE job_schema.jobs CASCADE");
        dataSource.reset();
        repository = new PostgresJobRepository(new NamedParameterJdbcTemplate(jdbc));
        service = new JobService(repository, url -> new JobLinkContentFetcher.JobLinkFetchResult(url, "unused", "unused", 200), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void searchJobsUsesBoundedQueryLoadingAndReportsAccurateCounts() {
        repository.saveJobAggregate(textAggregate(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Java Platform Engineer",
                "Build Java platforms",
                "hash-one"
        ));
        repository.saveJobAggregate(textAggregate(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Java Data Engineer",
                "Build Java pipelines",
                "hash-two"
        ));
        repository.saveJobAggregate(linkAggregate(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Python Engineer",
                "Build Python services",
                "https://example.test/jobs/3"
        ));
        dataSource.reset();

        JobService.JobSearchResult result = service.searchJobs(new JobService.JobSearchRequest("java", 1));

        assertEquals(2, result.totalMatches());
        assertEquals(1, result.returnedCount());
        assertEquals(1, result.jobs().size());
        assertEquals(4, dataSource.statementExecutions());
        int boundedRows = dataSource.rowsRead();

        for (int index = 0; index < 40; index++) {
            repository.saveJobAggregate(linkAggregate(
                    UUID.randomUUID(),
                    "Python Engineer " + index,
                    "Build Python services " + index,
                    "https://example.test/python/" + index
            ));
        }
        dataSource.reset();

        JobService.JobSearchResult grownCorpus = service.searchJobs(new JobService.JobSearchRequest("java", 1));

        assertEquals(2, grownCorpus.totalMatches());
        assertEquals(result.jobs(), grownCorpus.jobs());
        assertEquals(4, dataSource.statementExecutions());
        assertEquals(boundedRows, dataSource.rowsRead());

        dataSource.reset();
        var firstPage = repository.listJobs(PageRequest.of(1, null, "jobs", "all"));
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.nextCursor());
        assertEquals(1, dataSource.statementExecutions());
        assertEquals(2, dataSource.rowsRead());

        var secondPage = repository.listJobs(PageRequest.of(1, firstPage.nextCursor(), "jobs", "all"));
        assertEquals(1, secondPage.items().size());
        assertNotEquals(firstPage.items().getFirst().id(), secondPage.items().getFirst().id());

        assertEquals(null, repository.listJobs(PageRequest.of(100, null, "jobs", "all")).nextCursor());
        assertEquals(0, service.searchJobs(new JobService.JobSearchRequest("rust", 1)).totalMatches());
    }

    @Test
    void searchUsesCanonicalUnicodeTermsAndIndexedPrefixPlan() {
        repository.saveJobAggregate(textAggregate(UUID.randomUUID(),
                "Développeur Café\u0301", "Systèmes distribués à Montréal", "unicode-job"));

        var result = service.searchJobs(new JobService.JobSearchRequest("developpeur cafe montreal", 10));

        assertEquals(1, result.totalMatches());
        assertTrue(result.jobs().getFirst().matchedFields().contains("job.title"));
        jdbc.execute("SET enable_seqscan = off");
        var plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                WITH query_tokens(token) AS (VALUES ('dev')),
                query_prefixes AS (
                    SELECT token query_token, left(token, length) prefix
                    FROM query_tokens CROSS JOIN LATERAL generate_series(1, length(token)) length
                ), posting_hits AS (
                    SELECT posting.job_id, posting.field_key, query.token, posting.weight
                    FROM query_tokens query JOIN job_schema.search_terms posting
                      ON posting.term >= query.token AND posting.term < query.token || '~'
                    UNION
                    SELECT posting.job_id, posting.field_key, prefix.query_token, posting.weight
                    FROM query_prefixes prefix JOIN job_schema.search_terms posting ON posting.term = prefix.prefix
                )
                SELECT job_id, SUM(weight) FROM posting_hits GROUP BY job_id LIMIT 501
                """, String.class));
        assertTrue(plan.contains("job_search_terms_term_prefix_idx"), plan);
    }

    @Test
    void opaqueCursorSurvivesAnchorDeleteAndUpdatesAndExcludesLaterInserts() {
        var firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        repository.saveJobAggregate(textAggregate(firstId, "Alpha", "Java", "cursor-one"));
        repository.saveJobAggregate(textAggregate(secondId, "Beta", "Java", "cursor-two"));
        repository.saveJobAggregate(textAggregate(thirdId, "Gamma", "Java", "cursor-three"));

        var firstPage = repository.listJobs(PageRequest.of(1, null, "jobs", "all"));
        var resumed = PageRequest.of(1, firstPage.nextCursor(), "jobs", "all");
        repository.deleteJob(firstPage.items().getFirst().id());
        var second = repository.findJobAggregate(secondId).orElseThrow();
        var renamed = second.job();
        repository.updateJobAggregate(new JobAggregate(new JobPosting(renamed.id(), renamed.sourceMethod(),
                renamed.sourceLabel(), "Renamed", renamed.company(), renamed.location(), renamed.description(),
                renamed.experienceRequirement(), renamed.employmentType(), renamed.seniority(), renamed.postedAt(),
                renamed.canonicalFingerprint(), renamed.createdAt(), renamed.updatedAt().plusSeconds(1)),
                second.skills(), second.linkIngestion(), second.textIngestion()));
        repository.saveJobAggregate(textAggregate(UUID.randomUUID(), "Later", "Java", "cursor-later",
                resumed.cursor().snapshotAt().plusSeconds(1)));

        var secondPage = repository.listJobs(resumed);
        var thirdPage = repository.listJobs(PageRequest.of(1, secondPage.nextCursor(), "jobs", "all"));

        assertEquals(secondId, secondPage.items().getFirst().id());
        assertEquals(thirdId, thirdPage.items().getFirst().id());
        assertNull(thirdPage.nextCursor());
    }

    @Test
    void matchingCorpusIsCappedBeforeAggregateHydrationAndReportsLowerBoundMetadata() {
        for (int index = 0; index <= PostgresJobRepository.MAX_SEARCH_CANDIDATES; index++) {
            repository.saveJobAggregate(textAggregate(UUID.randomUUID(), "Java Engineer " + index,
                    "Java platform", "scale-" + index));
        }
        dataSource.reset();

        var result = service.searchJobs(new JobService.JobSearchRequest("java", 1));

        assertNull(result.totalMatches());
        assertEquals(PostgresJobRepository.MAX_SEARCH_CANDIDATES, result.matchedCount());
        assertTrue(result.hasMore());
        assertEquals(1, result.returnedCount());
        assertEquals(4, dataSource.statementExecutions());
        assertTrue(dataSource.rowsRead() <= 2_000, "bounded hydration read " + dataSource.rowsRead() + " rows");
    }

    private static JobAggregate textAggregate(UUID jobId, String title, String description, String textHash) {
        return textAggregate(jobId, title, description, textHash, NOW);
    }

    private static JobAggregate textAggregate(UUID jobId, String title, String description, String textHash, Instant createdAt) {
        return new JobAggregate(
                new JobPosting(
                        jobId,
                        "text",
                        "manual paste",
                        title,
                        "Example Corp",
                        "Remote",
                        description,
                        null,
                        null,
                        null,
                        NOW,
                        title.toLowerCase(),
                        createdAt,
                        createdAt
                ),
                List.of(new JobSkill(UUID.randomUUID(), jobId, "Java", "java", true, 0, createdAt)),
                null,
                new org.instruct.jobenginespring.domain.job.JobTextIngestion(UUID.randomUUID(), jobId, "manual paste", textHash, createdAt)
        );
    }

    private static JobAggregate linkAggregate(UUID jobId, String title, String description, String normalizedUrl) {
        return new JobAggregate(
                new JobPosting(
                        jobId,
                        "link",
                        "Example ATS",
                        title,
                        "Example Corp",
                        "Remote",
                        description,
                        null,
                        null,
                        null,
                        NOW,
                        title.toLowerCase(),
                        NOW,
                        NOW
                ),
                List.of(new JobSkill(UUID.randomUUID(), jobId, "Python", "python", true, 0, NOW)),
                new JobLinkIngestion(UUID.randomUUID(), jobId, normalizedUrl, normalizedUrl, NOW, 200, title, NOW),
                null
        );
    }
}
