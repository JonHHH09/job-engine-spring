package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobProvenanceMigrationIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    void migrationFailsClearlyWhenExistingJobsHaveInvalidProvenance() {
        try (PostgreSQLContainer<?> postgres = postgres()) {
            postgres.start();
            migrate(postgres, "13");
            JdbcTemplate jdbc = jdbc(postgres);
            insertJob(jdbc, UUID.randomUUID(), "text", "missing-provenance");

            FlywayException exception = assertThrows(FlywayException.class, () -> migrate(postgres, null));

            assertTrue(exception.getMessage().contains("existing job provenance violation"));
        }
    }

    @Test
    void migrationRejectsExistingJobsWithConflictingProvenance() {
        try (PostgreSQLContainer<?> postgres = postgres()) {
            postgres.start();
            migrate(postgres, "13");
            JdbcTemplate jdbc = jdbc(postgres);
            UUID jobId = UUID.randomUUID();
            insertLinkJob(jdbc, jobId, "conflicting");
            insertTextProvenance(jdbc, jobId, "conflicting");

            FlywayException exception = assertThrows(FlywayException.class, () -> migrate(postgres, null));

            assertTrue(exception.getMessage().contains("existing job provenance violation"));
        }
    }

    @Test
    void migrationRejectsExistingJobsWhoseProvenanceDoesNotMatchSourceMethod() {
        try (PostgreSQLContainer<?> postgres = postgres()) {
            postgres.start();
            migrate(postgres, "13");
            JdbcTemplate jdbc = jdbc(postgres);
            UUID jobId = UUID.randomUUID();
            insertJob(jdbc, jobId, "link", "mismatched");
            insertTextProvenance(jdbc, jobId, "mismatched");

            FlywayException exception = assertThrows(FlywayException.class, () -> migrate(postgres, null));

            assertTrue(exception.getMessage().contains("existing job provenance violation"));
        }
    }

    @Test
    void deferredTriggerRevalidatesBothJobsWhenProvenanceIsReparented() throws Exception {
        try (PostgreSQLContainer<?> postgres = postgres()) {
            postgres.start();
            migrate(postgres, "13");
            JdbcTemplate jdbc = jdbc(postgres);
            UUID sourceJobId = UUID.randomUUID();
            UUID targetJobId = UUID.randomUUID();
            UUID sourceLinkId = insertLinkJob(jdbc, sourceJobId, "source");
            UUID targetLinkId = insertLinkJob(jdbc, targetJobId, "target");
            migrate(postgres, null);

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                try (var delete = connection.prepareStatement("DELETE FROM job_schema.job_link_ingestions WHERE id = ?");
                     var reparent = connection.prepareStatement("UPDATE job_schema.job_link_ingestions SET job_id = ? WHERE id = ?")) {
                    delete.setObject(1, targetLinkId);
                    delete.executeUpdate();
                    reparent.setObject(1, targetJobId);
                    reparent.setObject(2, sourceLinkId);
                    reparent.executeUpdate();
                }

                assertThrows(Exception.class, connection::commit);
                connection.rollback();
            }
        }
    }

    private static UUID insertLinkJob(JdbcTemplate jdbc, UUID jobId, String key) {
        insertJob(jdbc, jobId, "link", key);
        UUID linkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_schema.job_link_ingestions (
                    id, job_id, url, normalized_url, fetched_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, linkId, jobId, "https://example.test/jobs/" + key,
                "https://example.test/jobs/" + key, Timestamp.from(NOW), Timestamp.from(NOW));
        return linkId;
    }

    private static void insertTextProvenance(JdbcTemplate jdbc, UUID jobId, String key) {
        jdbc.update("""
                INSERT INTO job_schema.job_text_ingestions (
                    id, job_id, source_label, input_text_hash, created_at
                ) VALUES (?, ?, 'migration-test', ?, ?)
                """, UUID.randomUUID(), jobId, "text-hash-" + key, Timestamp.from(NOW));
    }

    private static void insertJob(JdbcTemplate jdbc, UUID jobId, String sourceMethod, String key) {
        jdbc.update("""
                INSERT INTO job_schema.jobs (
                    id, source_method, title, description, canonical_fingerprint, created_at, updated_at
                ) VALUES (?, ?, 'Platform Engineer', 'Build platforms', ?, ?, ?)
                """, jobId, sourceMethod, "fingerprint-" + key, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:18-alpine")
                .withDatabaseName("job_engine")
                .withUsername("test")
                .withPassword("test");
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer<?> postgres) {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        ));
    }

    private static void migrate(PostgreSQLContainer<?> postgres, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
