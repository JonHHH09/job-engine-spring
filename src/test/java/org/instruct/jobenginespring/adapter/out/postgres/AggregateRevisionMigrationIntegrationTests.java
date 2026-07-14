package org.instruct.jobenginespring.adapter.out.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateRevisionMigrationIntegrationTests {

    @Test
    void validatesBothRevisionConstraintsOnlyAfterTheFollowUpMigrationCommits() {
        try (var postgres = postgres()) {
            postgres.start();
            migrate(postgres, "19");
            var jdbc = jdbc(postgres);

            assertFalse(isValidated(jdbc, "profile", "profiles_revision_non_negative"));
            assertFalse(isValidated(jdbc, "job_schema", "jobs_revision_non_negative"));

            migrate(postgres, null);

            assertTrue(isValidated(jdbc, "profile", "profiles_revision_non_negative"));
            assertTrue(isValidated(jdbc, "job_schema", "jobs_revision_non_negative"));
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

    private static boolean isValidated(JdbcTemplate jdbc, String schema, String constraint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT constraint_definition.convalidated
                FROM pg_catalog.pg_constraint constraint_definition
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = constraint_definition.connamespace
                WHERE namespace.nspname = ?
                  AND constraint_definition.conname = ?
                """, Boolean.class, schema, constraint));
    }
}
