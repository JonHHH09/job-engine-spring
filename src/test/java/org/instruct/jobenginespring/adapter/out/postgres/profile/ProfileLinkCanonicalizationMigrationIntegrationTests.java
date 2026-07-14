package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileLinkCanonicalizationMigrationIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Test
    void canonicalizesLegacyProfileLinksBeforeExactIdentityLookupIsUsed() {
        try (var postgres = postgres()) {
            postgres.start();
            migrate(postgres, "18");
            var jdbc = jdbc(postgres);
            UUID profileId = insertProfile(jdbc, "legacy@example.test");
            insertLink(jdbc, profileId, "HTTP://EXAMPLE.test:80/me/?ref=legacy#bio");

            migrate(postgres, null);

            assertEquals("https://example.test/me", jdbc.queryForObject(
                    "SELECT url FROM profile.profile_links WHERE profile_id = ?",
                    String.class,
                    profileId
            ));
        }
    }

    @Test
    void failsClearlyWhenCanonicalizationWouldCollapseLinksOnOneProfile() {
        try (var postgres = postgres()) {
            postgres.start();
            migrate(postgres, "18");
            var jdbc = jdbc(postgres);
            UUID profileId = insertProfile(jdbc, "collision@example.test");
            insertLink(jdbc, profileId, "https://example.test/me?source=one");
            insertLink(jdbc, profileId, "https://example.test/me#source-two");

            FlywayException exception = assertThrows(FlywayException.class, () -> migrate(postgres, null));

            assertTrue(exception.getMessage().contains("canonical profile link conflict"));
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

    private static UUID insertProfile(JdbcTemplate jdbc, String email) {
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO profile.profiles (id, full_name, email, created_at, updated_at)
                VALUES (?, 'Legacy Profile', ?, ?, ?)
                """, profileId, email, Timestamp.from(NOW), Timestamp.from(NOW));
        return profileId;
    }

    private static void insertLink(JdbcTemplate jdbc, UUID profileId, String url) {
        jdbc.update("""
                INSERT INTO profile.profile_links (
                    id, profile_id, link_type, url, created_at, updated_at
                ) VALUES (?, ?, 'portfolio', ?, ?, ?)
                """, UUID.randomUUID(), profileId, url, Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
