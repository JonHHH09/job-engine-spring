package org.instruct.jobenginespring.application.job;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.job.PostgresJobRepository;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.document.GermanCoverLetterPersistenceService;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.application.search.SearchTextNormalizer;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobLinkIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.support.CountingDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
        service = new JobService(
                repository,
                url -> new JobLinkContentFetcher.JobLinkFetchResult(url, "unused", "unused", 200),
                mock(GermanCoverLetterPersistenceService.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
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
        var latinId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        repository.saveJobAggregate(textAggregate(latinId, "Platform Developer", "Build Systems", "latin-search-hash"));
        assertEquals(latinId, service.searchJobs(new JobService.JobSearchRequest("platform", 10))
                .jobs().getFirst().job().id());
        seedCommonPrefixSearchCorpus(5_000);
        var plan = explainProductionSearch("developpeur");
        assertTrue(plan.contains("job_search_terms_term_prefix_idx"), plan);
        assertTrue(indexRowsExamined(plan, "job_search_terms_term_prefix_idx") <= 8_018, plan);
        assertFalse(plan.contains("Seq Scan on search_terms"), plan);
        assertFalse(plan.contains("Seq Scan on jobs"), plan);
    }

    @Test
    void opaqueCursorSurvivesAnchorDeleteAndUpdatesAndExcludesRowsWithLaterCreatedAt() {
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
                renamed.canonicalFingerprint(), renamed.createdAt(), renamed.updatedAt().plusSeconds(1),
                renamed.revision() + 1), second.skills(), second.linkIngestion(), second.textIngestion()),
                renamed.revision());
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
        var expectedIds = IntStream.rangeClosed(0, PostgresJobRepository.MAX_SEARCH_CANDIDATES)
                .boxed()
                .sorted(java.util.Comparator.comparing(PostgresJobSearchIntegrationTests::candidateName)
                        .thenComparing(PostgresJobSearchIntegrationTests::candidateId))
                .map(PostgresJobSearchIntegrationTests::candidateId)
                .toList();
        for (int index = 0; index <= PostgresJobRepository.MAX_SEARCH_CANDIDATES; index++) {
            repository.saveJobAggregate(textAggregate(candidateId(index),
                    candidateName(index),
                    "Java platform", "scale-" + index));
        }
        dataSource.reset();

        var result = service.searchJobs(new JobService.JobSearchRequest("java", 1));

        assertNull(result.totalMatches());
        assertEquals(PostgresJobRepository.MAX_SEARCH_CANDIDATES, result.matchedCount());
        assertTrue(result.hasMore());
        assertEquals(1, result.returnedCount());
        assertEquals(expectedIds.getFirst(), result.jobs().getFirst().job().id());
        assertEquals(4, dataSource.statementExecutions());
        assertTrue(dataSource.rowsRead() <= 2_000, "bounded hydration read " + dataSource.rowsRead() + " rows");

        var candidates = repository.searchJobCandidates(List.of("java"), 1);
        assertEquals(expectedIds.subList(0, PostgresJobRepository.MAX_SEARCH_CANDIDATES),
                candidates.items().stream().map(item -> item.job().id()).toList());
    }

    @Test
    void postingCapReportsUnknownRemainderInsteadOfClaimingAnExactTotal() {
        var heavyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var laterId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        repository.saveJobAggregate(textAggregate(heavyId, "Heavy", "Other", "heavy-hash"));
        repository.saveJobAggregate(textAggregate(laterId, "Later", "Other", "later-hash"));
        jdbc.update("DELETE FROM job_schema.search_terms WHERE job_id IN (?, ?)", heavyId, laterId);
        jdbc.update("""
                INSERT INTO job_schema.search_terms (job_id, field_key, term, weight)
                SELECT ?::uuid, 'heavy:' || value, 'java', 1
                FROM generate_series(1, 4009) value
                """, heavyId);
        jdbc.update("""
                INSERT INTO job_schema.search_terms (job_id, field_key, term, weight)
                VALUES (?, 'later:skill', 'java', 1)
                """, laterId);

        var candidates = repository.searchJobCandidates(List.of("java"), 10);

        assertEquals(1, candidates.matchedCount());
        assertTrue(candidates.hasMore());
        assertEquals(List.of(heavyId), candidates.items().stream().map(item -> item.job().id()).toList());
    }

    private static UUID candidateId(int index) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(index + 1L));
    }

    private static String candidateName(int index) {
        return (index % 2 == 0 ? "Java Développeur " : "Java Ångström ") + index;
    }

    private static void seedCommonPrefixSearchCorpus(int count) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO job_schema.jobs (
                        id, source_method, source_label, title, description, canonical_fingerprint,
                        created_at, updated_at)
                    SELECT md5('plan-job-' || value)::uuid, 'text', 'plan-scale',
                           'Noise ' || value, 'Noise corpus', 'plan-fingerprint-' || value,
                           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    FROM generate_series(1, ?) value
                    """, count);
            jdbc.update("""
                    INSERT INTO job_schema.job_text_ingestions (
                        id, job_id, source_label, input_text_hash, created_at)
                    SELECT md5('plan-ingestion-' || id)::uuid, id, 'plan-scale',
                           'plan-input-hash-' || id, CURRENT_TIMESTAMP
                    FROM job_schema.jobs
                    WHERE source_label = 'plan-scale'
                    """);
            jdbc.update("""
                    INSERT INTO job_schema.search_terms (job_id, field_key, term, weight)
                    SELECT job.id, prefix.field_key, prefix.term, 8
                    FROM job_schema.jobs job
                    CROSS JOIN (VALUES
                        ('prefix.d', 'd'), ('prefix.de', 'de'),
                        ('prefix.dev', 'dev'), ('prefix.deve', 'deve')
                    ) prefix(field_key, term)
                    WHERE source_label = 'plan-scale'
                    """);
        });
        jdbc.execute("ANALYZE job_schema.jobs");
        jdbc.execute("ANALYZE job_schema.search_terms");
    }

    private static String explainProductionSearch(String token) {
        var prefixes = SearchTextNormalizer.prefixes(List.of(token));
        var parameters = new MapSqlParameterSource()
                .addValue("queryTokens", new String[]{token})
                .addValue("queryPrefixes", prefixes.values().toArray(String[]::new))
                .addValue("prefixOwners", prefixes.owners().toArray(String[]::new))
                .addValue("postingLimit", 4_008)
                .addValue("postingFetchLimit", 4_009)
                .addValue("fetchLimit", PostgresJobRepository.MAX_SEARCH_CANDIDATES + 1);
        return String.join("\n", new NamedParameterJdbcTemplate(jdbc).query(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + PostgresJobRepository.SEARCH_CANDIDATES_SQL,
                parameters, (resultSet, rowNumber) -> resultSet.getString(1)));
    }

    private static long indexRowsExamined(String plan, String indexName) {
        var rows = Pattern.compile("actual [^)]*rows=(\\d+(?:\\.\\d+)?) loops=(\\d+)");
        return plan.lines().filter(line -> line.contains(indexName)).mapToLong(line -> {
            var matcher = rows.matcher(line);
            assertTrue(matcher.find(), line);
            return (long) Math.ceil(Double.parseDouble(matcher.group(1)) * Long.parseLong(matcher.group(2)));
        }).sum();
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
