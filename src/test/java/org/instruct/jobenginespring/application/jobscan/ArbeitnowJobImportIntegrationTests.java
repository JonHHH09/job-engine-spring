package org.instruct.jobenginespring.application.jobscan;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.job.PostgresJobRepository;
import org.instruct.jobenginespring.application.document.GermanCoverLetterPersistenceService;
import org.instruct.jobenginespring.application.job.JobService;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.testsupport.PostgresTestContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Testcontainers
class ArbeitnowJobImportIntegrationTests {

    private static final Instant TOKEN_NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = PostgresTestContainers.postgres("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clearJobs() {
        jdbc.update("TRUNCATE TABLE job_schema.jobs CASCADE");
    }

    @Test
    void rollsBackEveryArtifactWhenVerifiedCandidatePersistenceFailsAfterProvenanceAndSkills() {
        installSearchTermFailureTrigger();
        try (var context = applicationContext()) {
            ArbeitnowCandidateTokenCodec codec = context.getBean(ArbeitnowCandidateTokenCodec.class);
            ArbeitnowJobImportService imports = context.getBean(ArbeitnowJobImportService.class);
            JobService jobs = context.getBean(JobService.class);
            String token = codec.issue(candidate());

            assertEquals(candidate(), codec.verify(token));
            assertTrue(AopUtils.isAopProxy(jobs));
            DataAccessException failure = assertThrows(DataAccessException.class, () -> imports.importCandidate(token));
            SQLException sqlFailure = deepestSqlCause(failure);
            assertEquals("P0001", sqlFailure.getSQLState());
            assertTrue(sqlFailure.getMessage().contains("OPEN-108 deliberate search-term persistence failure"));
        } finally {
            removeSearchTermFailureTrigger();
        }

        assertNoImportArtifacts();
    }

    @Test
    void importedCandidateUsesGenericListSearchGetUpdateDeleteAndIdempotentProvenance() {
        try (var context = applicationContext()) {
            ArbeitnowCandidateTokenCodec codec = context.getBean(ArbeitnowCandidateTokenCodec.class);
            ArbeitnowJobImportService imports = context.getBean(ArbeitnowJobImportService.class);
            JobService jobs = context.getBean(JobService.class);
            String token = codec.issue(candidate());

            JobService.AddJobResult created = imports.importCandidate(token);
            JobService.AddJobResult reused = imports.importCandidate(token);
            var jobId = created.job().job().id();

            assertEquals("created_job", created.status());
            assertEquals("reused_existing_job", reused.status());
            assertEquals(jobId, reused.job().job().id());
            assertEquals(List.of(jobId), jobs.listJobs().stream().map(job -> job.id()).toList());
            assertEquals(1, jobs.searchJobs(new JobService.JobSearchRequest("platform spring", 10)).totalMatches());
            assertEquals(jobId, jobs.searchJobs(new JobService.JobSearchRequest("platform spring", 10))
                    .jobs().getFirst().job().id());

            var persisted = jobs.getJob(jobId).orElseThrow();
            assertEquals("link", persisted.job().sourceMethod());
            assertEquals("arbeitnow", persisted.job().sourceLabel());
            assertEquals("https://arbeitnow.com/view/platform-java-engineer", persisted.linkIngestion().normalizedUrl());
            assertEquals("Arbeitnow scan candidate (remote=true)", persisted.linkIngestion().sourceTitle());
            assertEquals(List.of("java", "spring"), persisted.skills().stream()
                    .map(skill -> skill.normalizedSkill()).toList());
            assertEquals(1, count("job_schema.jobs"));
            assertEquals(1, count("job_schema.job_link_ingestions"));

            var updated = jobs.updateJob(new JobService.UpdateJobRequest(
                    jobId, 0L, null, "Senior Platform Java Engineer", null, null,
                    "Build resilient platform services", List.of("Kotlin", "Java"), null, null, null, null
            ));

            assertEquals(1L, updated.job().revision());
            assertEquals("arbeitnow", updated.job().sourceLabel());
            assertEquals("Senior Platform Java Engineer", jobs.getJob(jobId).orElseThrow().job().title());
            assertEquals(List.of("kotlin", "java"), jobs.getJob(jobId).orElseThrow().skills().stream()
                    .map(skill -> skill.normalizedSkill()).toList());
            assertEquals(new JobService.DeleteJobResult(jobId, true), jobs.deleteJob(jobId));
            assertTrue(jobs.getJob(jobId).isEmpty());
            assertTrue(jobs.listJobs().isEmpty());
            assertEquals(0, jobs.searchJobs(new JobService.JobSearchRequest("platform", 10)).totalMatches());
        }

        assertNoImportArtifacts();
    }

    private static AnnotationConfigApplicationContext applicationContext() {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(JobRepository.class,
                () -> new PostgresJobRepository(new NamedParameterJdbcTemplate(dataSource)));
        context.registerBean(JobLinkContentFetcher.class,
                () -> url -> { throw new AssertionError("Arbeitnow import must not fetch " + url); });
        context.registerBean(GermanCoverLetterPersistenceService.class,
                () -> mock(GermanCoverLetterPersistenceService.class));
        context.registerBean(ArbeitnowCandidateTokenCodec.class,
                () -> new ArbeitnowCandidateTokenCodec(Clock.fixed(TOKEN_NOW, ZoneOffset.UTC), new byte[32]));
        context.registerBean(PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.register(JobService.class, ArbeitnowJobImportService.class);
        context.refresh();
        return context;
    }

    private static ArbeitnowCandidateTokenCodec.Candidate candidate() {
        return new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", TOKEN_NOW, TOKEN_NOW.plusSeconds(900), "platform-java-engineer",
                "https://arbeitnow.com/view/platform-java-engineer", "Example Systems", "Platform Java Engineer",
                "Berlin", true, Instant.parse("2026-09-17T08:00:00Z"), List.of("Java", "Spring"),
                List.of("Full-time"), "Build reliable platform services"
        );
    }

    private static void installSearchTermFailureTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION job_schema.open108_fail_search_term_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'OPEN-108 deliberate search-term persistence failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER open108_fail_search_term_insert
                BEFORE INSERT ON job_schema.search_terms
                FOR EACH ROW
                EXECUTE FUNCTION job_schema.open108_fail_search_term_insert()
                """);
    }

    private static void removeSearchTermFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS open108_fail_search_term_insert ON job_schema.search_terms");
        jdbc.execute("DROP FUNCTION IF EXISTS job_schema.open108_fail_search_term_insert()");
    }

    private static void assertNoImportArtifacts() {
        assertEquals(0, count("job_schema.jobs"));
        assertEquals(0, count("job_schema.job_link_ingestions"));
        assertEquals(0, count("job_schema.job_skills"));
        assertEquals(0, count("job_schema.search_terms"));
        assertEquals(0, count("job_schema.job_text_ingestions"));
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static SQLException deepestSqlCause(Throwable failure) {
        SQLException deepest = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                deepest = sqlException;
            }
        }
        if (deepest == null) {
            throw new AssertionError("Expected PostgreSQL failure cause", failure);
        }
        return deepest;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
