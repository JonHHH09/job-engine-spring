package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.instruct.jobenginespring.testsupport.PostgresTestContainers;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobUrlMigrationIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    void migrationScrubsSecretsAndPreservesOnlyPostingIdentity() {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            migrate(postgres, "12");
            JdbcTemplate jdbc = jdbc(postgres);
            UUID jobId = insertLinkJob(
                    jdbc,
                    "https://user:password@www.indeed.com/viewjob?jk=abc123def4567890&token=secret&utm_source=email#details",
                    "https://user:password@www.indeed.com/viewjob?token=secret&jk=abc123def4567890&utm_source=email#details",
                    "fingerprint-one"
            );

            migrate(postgres, null);

            Map<String, Object> link = jdbc.queryForMap("""
                    SELECT url, normalized_url
                    FROM job_schema.job_link_ingestions
                    WHERE job_id = ?
                    """, jobId);
            assertEquals("https://www.indeed.com/viewjob", link.get("url"));
            assertEquals("https://www.indeed.com/viewjob?jk=abc123def4567890", link.get("normalized_url"));
        }
    }

    @Test
    void migrationFailsClearlyWhenCanonicalizationWouldCollapseExistingJobs() {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            migrate(postgres, "12");
            JdbcTemplate jdbc = jdbc(postgres);
            insertLinkJob(
                    jdbc,
                    "https://example.test/jobs/view?token=one",
                    "https://example.test/jobs/view?token=one",
                    "fingerprint-one"
            );
            insertLinkJob(
                    jdbc,
                    "https://example.test/jobs/view?token=two",
                    "https://example.test/jobs/view?token=two",
                    "fingerprint-two"
            );

            FlywayException exception = assertThrows(FlywayException.class, () -> migrate(postgres, null));
            assertTrue(exception.getMessage().contains("canonical job URL conflict"));
        }
    }

    private static PostgreSQLContainer postgres() {
        return PostgresTestContainers.postgres("postgres:18-alpine")
                .withDatabaseName("job_engine")
                .withUsername("test")
                .withPassword("test");
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer postgres) {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ));
    }

    private static void migrate(PostgreSQLContainer postgres, String target) {
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

    private static UUID insertLinkJob(
            JdbcTemplate jdbc,
            String url,
            String normalizedUrl,
            String fingerprint
    ) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_schema.jobs (
                    id, source_method, title, description, canonical_fingerprint, created_at, updated_at
                ) VALUES (?, 'link', 'Platform Engineer', 'Build platforms', ?, ?, ?)
                """, jobId, fingerprint, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO job_schema.job_link_ingestions (
                    id, job_id, url, normalized_url, fetched_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), jobId, url, normalizedUrl, Timestamp.from(NOW), Timestamp.from(NOW));
        return jobId;
    }
}