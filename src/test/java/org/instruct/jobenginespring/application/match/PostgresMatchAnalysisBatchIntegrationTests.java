package org.instruct.jobenginespring.application.match;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.job.PostgresJobRepository;
import org.instruct.jobenginespring.adapter.out.postgres.match.PostgresMatchAnalysisRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileRepository;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.match.port.MatchAnalysisRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.match.MatchReport;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.support.CountingDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@Testcontainers
class PostgresMatchAnalysisBatchIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-07-14T15:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_JOB_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_JOB_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static CountingDataSource dataSource;
    private static JdbcTemplate jdbc;
    private PostgresProfileRepository profiles;
    private PostgresJobRepository jobs;
    private PostgresMatchAnalysisRepository matches;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema", "match")
                .load()
                .migrate();
        dataSource = new CountingDataSource(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE profile.profiles, job_schema.jobs CASCADE");
        var namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        profiles = new PostgresProfileRepository(namedJdbc);
        jobs = new PostgresJobRepository(namedJdbc);
        matches = spy(new PostgresMatchAnalysisRepository(JdbcClient.create(jdbc), new ObjectMapper()));
        profiles.saveProfileAggregate(profileAggregate());
        jobs.saveJobAggregate(jobAggregate(FIRST_JOB_ID, "hash-one"));
        jobs.saveJobAggregate(jobAggregate(SECOND_JOB_ID, "hash-two"));
        dataSource.reset();
    }

    @Test
    void singlePairLoadsAndPersistsInsideOneRequiresNewTransaction() {
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(matches).saveReport(any());

        try (var context = applicationContext()) {
            var result = context.getBean(MatchAnalysisService.class).analyze(PROFILE_ID, FIRST_JOB_ID);

            assertTrue(AopUtils.isAopProxy(context.getBean(MatchPairAnalyzer.class)));
            assertEquals(FIRST_JOB_ID, result.report().jobId());
            assertEquals(15, dataSource.statementExecutions());
        }
    }

    @Test
    void batchUsesOneProfileLoadListedJobsAndOneRequiresNewTransactionPerPair() {
        Set<Object> transactionResources = Collections.newSetFromMap(new IdentityHashMap<>());
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            transactionResources.add(TransactionSynchronizationManager.getResource(dataSource));
            return invocation.callRealMethod();
        }).when(matches).saveReport(any());

        try (var context = applicationContext()) {
            var pairAnalyzer = context.getBean(MatchPairAnalyzer.class);
            var service = context.getBean(MatchAnalysisService.class);

            var result = service.analyzeAll(PROFILE_ID);

            assertTrue(AopUtils.isAopProxy(pairAnalyzer));
            assertEquals(2, result.succeeded().size());
            assertTrue(result.failed().isEmpty());
            assertEquals(2, transactionResources.size());
            assertEquals(17, dataSource.statementExecutions());
        }
    }

    @Test
    void failedPairRollsBackWithoutPreventingTheNextPair() {
        doAnswer(invocation -> {
            var saved = (MatchReport) invocation.callRealMethod();
            if (FIRST_JOB_ID.equals(saved.jobId())) {
                throw new IllegalStateException("private persistence detail");
            }
            return saved;
        }).when(matches).saveReport(any());

        try (var context = applicationContext()) {
            var result = context.getBean(MatchAnalysisService.class).analyzeAll(PROFILE_ID);

            assertEquals(1, result.succeeded().size());
            assertEquals(SECOND_JOB_ID, result.succeeded().getFirst().report().jobId());
            assertEquals(List.of(new MatchAnalysisService.PairFailure(
                    PROFILE_ID, FIRST_JOB_ID, "analysis failed")), result.failed());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM match.reports", Integer.class));
        }
    }

    private AnnotationConfigApplicationContext applicationContext() {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(ProfileRepository.class, () -> profiles);
        context.registerBean(JobRepository.class, () -> jobs);
        context.registerBean(MatchAnalysisRepository.class, () -> matches);
        context.registerBean(PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.register(TransactionalMatchPairAnalyzer.class, MatchAnalysisService.class);
        context.refresh();
        return context;
    }

    private static ProfileAggregate profileAggregate() {
        return new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Candidate", "candidate@example.test", "Backend systems", null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "backend", 0, NOW)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static JobAggregate jobAggregate(UUID jobId, String hash) {
        return new JobAggregate(
                new JobPosting(jobId, "text", "test", "Java Engineer", "Example Corp", "Remote",
                        "Build Java services", null, null, null, NOW, hash, NOW, NOW),
                List.of(new JobSkill(UUID.randomUUID(), jobId, "Java", "java", true, 0, NOW)),
                null,
                new JobTextIngestion(UUID.randomUUID(), jobId, "test", hash, NOW)
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
