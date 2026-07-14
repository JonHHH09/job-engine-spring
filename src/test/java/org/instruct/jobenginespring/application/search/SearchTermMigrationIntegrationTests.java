package org.instruct.jobenginespring.application.search;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class SearchTermMigrationIntegrationTests {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine").withUsername("test").withPassword("test");

    @Test
    void javaBackfillUsesCanonicalNfkdNormalizationForLegacyRows() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .defaultSchema("profile").schemas("profile", "document", "job_schema", "match");
        configuration.target(MigrationVersion.fromVersion("18")).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var profileId = UUID.randomUUID();
        var now = Timestamp.from(Instant.parse("2026-07-14T12:00:00Z"));
        var batchTerms = IntStream.rangeClosed(0, 1_000).mapToObj(index -> "token" + index)
                .collect(Collectors.joining(" "));
        jdbc.update("""
                INSERT INTO profile.profiles (id, full_name, email, summary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, profileId, "José Cafe\u0301", "jose@example.test", "Développement " + batchTerms, now, now);
        jdbc.update("""
                INSERT INTO profile.profiles (id, full_name, email, summary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "Remainder Profile", "remainder@example.test", "final batch", now, now);

        configuration.target(MigrationVersion.LATEST).load().migrate();

        assertEquals(3, jdbc.queryForObject("""
                SELECT count(DISTINCT term) FROM profile.search_terms
                WHERE profile_id = ? AND term IN ('jose', 'cafe', 'developpement')
                """, Integer.class, profileId));
    }
}
