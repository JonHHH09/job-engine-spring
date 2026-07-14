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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void reportsSanitizedBacklogMetricsAndRepeatedFailures() {
        UUID pending = repository.enqueue("/private/users/jh/pending.pdf", NOW.minusSeconds(600));
        UUID processing = repository.enqueue("/private/users/jh/processing.pdf", NOW.minusSeconds(300));
        repository.claim(processing, NOW, NOW.plusSeconds(300));
        jdbc.update("""
                UPDATE document.generated_resume_file_cleanups
                SET attempt_count = 3, last_failure_type = 'FILE_DELETE_FAILED', next_attempt_at = ?
                WHERE id = ?
                """, java.sql.Timestamp.from(NOW.minusSeconds(120)), pending);

        var snapshot = repository.readQueueSnapshot(NOW, 3);

        assertEquals(1, snapshot.pendingCount());
        assertEquals(1, snapshot.processingCount());
        assertEquals(NOW.minusSeconds(120), snapshot.oldestDueAt());
        assertTrue(snapshot.repeatedFailure());
        assertTrue(snapshot.toString().contains("pendingCount=1"));
        assertTrue(!snapshot.toString().contains("/private/users"));
    }

    @Test
    void emptyQueueHasNoOldestDueTask() {
        var snapshot = repository.readQueueSnapshot(NOW, 3);

        assertEquals(0, snapshot.pendingCount());
        assertEquals(0, snapshot.processingCount());
        assertNull(snapshot.oldestDueAt());
    }

    @Test
    void rejectsInvalidObservationAndRetentionLimits() {
        assertThrows(IllegalArgumentException.class, () -> repository.readQueueSnapshot(NOW, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.deleteCompletedBefore(NOW, 0));
    }

    @Test
    void retentionDeletesOnlyOldCompletedRowsAndHonorsBatchLimit() {
        UUID first = completedTask("first.pdf", NOW.minusSeconds(10_000));
        UUID second = completedTask("second.pdf", NOW.minusSeconds(9_000));
        UUID recent = completedTask("recent.pdf", NOW.minusSeconds(100));
        UUID pending = repository.enqueue("pending.pdf", NOW.minusSeconds(20_000));

        assertEquals(1, repository.deleteCompletedBefore(NOW.minusSeconds(1_000), 1));
        assertEquals(1, repository.deleteCompletedBefore(NOW.minusSeconds(1_000), 10));

        assertEquals(List.of(recent, pending), jdbc.queryForList("""
                SELECT id FROM document.generated_resume_file_cleanups ORDER BY created_at DESC, id
                """, UUID.class));
        assertTrue(!List.of(first, second).contains(recent));
    }

    private UUID completedTask(String path, Instant completedAt) {
        UUID taskId = repository.enqueue(path, completedAt.minusSeconds(60));
        repository.claim(taskId, completedAt.minusSeconds(60), completedAt.plusSeconds(240));
        repository.markCompleted(taskId, completedAt);
        return taskId;
    }
}
