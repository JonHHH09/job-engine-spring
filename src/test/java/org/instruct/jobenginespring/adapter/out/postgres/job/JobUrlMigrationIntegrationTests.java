package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

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
        try (PostgreSQLContainer<?> postgres = postgres()) {
            postgres.start();
            migrate(postgres, "12");
            JdbcTemplate jdbc = jdbc(postgres);
            UUID jobId = insertLinkJob(
                    jdbc,
                    "https://user:password@www.indeed.com/viewjob?jk=abc123def4567890&token=secret&utm_source=email#details",
                    "https://user:password@www.indeed.com/viewjob?token=secret&jk=abc123def4567890&utm_source=email#details",
                    "fingerprint-one"
            );
            UUID analysisId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO job_schema.job_analysis_runs (
                        id, source_type, original_url, normalized_url, fetch_status,
                        input_sha256, input_json, hermes_status, validation_status,
                        validation_errors, created_at, updated_at
                    ) VALUES (?, 'link', ?, ?, 'FETCHED', 'input-hash', ?::jsonb,
                              'SUCCEEDED', 'VALID', '[]'::jsonb, ?, ?)
                    """,
                    analysisId,
                    "https://boards.greenhouse.io/example/jobs/123?gh_jid=456789&gh_src=tracking-source&token=secret#details",
                    "https://boards.greenhouse.io/example/jobs/123?token=secret&gh_src=tracking-source&gh_jid=456789#details",
                    "{\"originalUrl\":\"https://boards.greenhouse.io/example/jobs/123?token=secret\",\"normalizedUrl\":\"https://boards.greenhouse.io/example/jobs/123?gh_src=tracking-source&gh_jid=456789\"}",
                    Timestamp.from(NOW),
                    Timestamp.from(NOW)
            );

            migrate(postgres, null);

            Map<String, Object> link = jdbc.queryForMap("""
                    SELECT url, normalized_url
                    FROM job_schema.job_link_ingestions
                    WHERE job_id = ?
                    """, jobId);
            assertEquals("https://www.indeed.com/viewjob", link.get("url"));
            assertEquals("https://www.indeed.com/viewjob?jk=abc123def4567890", link.get("normalized_url"));

            Map<String, Object> analysis = jdbc.queryForMap("""
                    SELECT original_url, normalized_url,
                           input_json ->> 'originalUrl' AS input_original_url,
                           input_json ->> 'normalizedUrl' AS input_normalized_url
                    FROM job_schema.job_analysis_runs
                    WHERE id = ?
                    """, analysisId);
            assertEquals("https://boards.greenhouse.io/example/jobs/123", analysis.get("original_url"));
            assertEquals("https://boards.greenhouse.io/example/jobs/123?gh_jid=456789", analysis.get("normalized_url"));
            assertEquals(analysis.get("original_url"), analysis.get("input_original_url"));
            assertEquals(analysis.get("normalized_url"), analysis.get("input_normalized_url"));
            assertTrue(analysis.values().stream().noneMatch(value -> String.valueOf(value).contains("secret")));
        }
    }

    @Test
    void migrationFailsClearlyWhenCanonicalizationWouldCollapseExistingJobs() {
        try (PostgreSQLContainer<?> postgres = postgres()) {
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

    private static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:18-alpine")
                .withDatabaseName("job_engine")
                .withUsername("test")
                .withPassword("test");
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer<?> postgres) {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
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