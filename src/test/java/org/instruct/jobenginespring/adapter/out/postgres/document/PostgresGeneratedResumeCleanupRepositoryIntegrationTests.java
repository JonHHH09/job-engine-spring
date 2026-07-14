package org.instruct.jobenginespring.adapter.out.postgres.document;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresGeneratedResumeCleanupRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;
    private PostgresGeneratedResumeCleanupRepository repository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE document.generated_resume_file_cleanups");
        repository = new PostgresGeneratedResumeCleanupRepository(JdbcClient.create(jdbc));
    }

    @Test
    void pendingTaskCanBeClaimedFailedRetriedAndCompleted() {
        UUID taskId = repository.enqueue("private.pdf", NOW);

        assertEquals(java.util.List.of(taskId), repository.findDueTaskIds(NOW, 25));
        assertEquals("private.pdf", repository.claim(taskId, NOW, NOW.plusSeconds(300)).orElseThrow());
        assertTrue(repository.claim(taskId, NOW, NOW.plusSeconds(300)).isEmpty());

        repository.markPending(taskId, NOW.plusSeconds(60), "IOException");
        assertTrue(repository.findDueTaskIds(NOW, 25).isEmpty());
        assertEquals(java.util.List.of(taskId), repository.findDueTaskIds(NOW.plusSeconds(60), 25));
        assertEquals("private.pdf", repository.claim(
                taskId, NOW.plusSeconds(60), NOW.plusSeconds(360)
        ).orElseThrow());
        repository.markCompleted(taskId, NOW.plusSeconds(60));

        assertTrue(repository.findDueTaskIds(NOW.plusSeconds(600), 25).isEmpty());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM document.generated_resume_file_cleanups WHERE id = ?", String.class, taskId
        ));
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM document.generated_resume_file_cleanups WHERE id = ?", Integer.class, taskId
        ));
    }

    @Test
    void cleanupTaskRetainsExactGeneratedDocumentId() {
        UUID documentId = UUID.randomUUID();

        UUID taskId = repository.enqueue(documentId, "private.pdf", NOW);

        assertEquals(documentId, repository.findDocumentId(taskId).orElseThrow());
    }
}
