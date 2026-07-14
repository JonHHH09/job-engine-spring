package org.instruct.jobenginespring.application.search;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.instruct.jobenginespring.support.CountingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class SearchTermMigrationIntegrationTests {
    private static final long BLOCKING_ADVISORY_LOCK = 65_120L;
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine").withUsername("test").withPassword("test");

    @Test
    void javaBackfillUsesCanonicalNfkdNormalizationForLegacyRows() {
        var dataSource = dataSource();
        resetTo(dataSource, "18");
        var jdbc = new JdbcTemplate(dataSource);
        var profileId = UUID.randomUUID();
        var now = Timestamp.from(Instant.parse("2026-07-14T12:00:00Z"));
        var batchTerms = IntStream.rangeClosed(0, 1_000).mapToObj(index -> "token" + index)
                .collect(Collectors.joining(" "));
        insertProfile(jdbc, profileId, "José Cafe\u0301", "jose@example.test",
                "Développement " + batchTerms, now);
        insertProfile(jdbc, UUID.randomUUID(), "Remainder Profile", "remainder@example.test", "final batch", now);

        configuration(dataSource).target(MigrationVersion.LATEST).load().migrate();

        assertEquals(3, jdbc.queryForObject("""
                SELECT count(DISTINCT term) FROM profile.search_terms
                WHERE profile_id = ? AND term IN ('jose', 'cafe', 'developpement')
                """, Integer.class, profileId));
    }

    @Test
    void largeCorpusKeepsEntityChildResultSetsFetchesAndInsertBatchesBounded() {
        var delegate = dataSource();
        resetTo(delegate, "18");
        var jdbc = new JdbcTemplate(delegate);
        var now = Timestamp.from(Instant.parse("2026-07-14T12:00:00Z"));
        var longSummary = IntStream.rangeClosed(0, 1_000).mapToObj(index -> "summary" + index)
                .collect(Collectors.joining(" "));
        var firstProfile = deterministicUuid("profile-0");
        for (int index = 0; index < 205; index++) {
            insertProfile(jdbc, deterministicUuid("profile-" + index), "Profile " + index,
                    "profile" + index + "@example.test", index == 0 ? longSummary : "bounded summary " + index, now);
        }
        for (int index = 0; index < 260; index++) {
            jdbc.update("""
                    INSERT INTO profile.profile_contacts
                        (id, profile_id, contact_type, contact_value, label, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, deterministicUuid("contact-" + index), firstProfile, "type" + index,
                    "value" + index, "label" + index, now, now);
        }
        var counted = new CountingDataSource(delegate);

        configuration(counted).target(MigrationVersion.LATEST).load().migrate();

        assertEquals(205, jdbc.queryForObject(
                "SELECT count(DISTINCT profile_id) FROM profile.search_terms", Integer.class));
        assertEquals(100, counted.maxFetchSize());
        assertTrue(counted.maxRowsReadPerResultSet() <= 750, "largest result set was " + counted.maxRowsReadPerResultSet());
        assertEquals(1_000, counted.maxBatchSize());
    }

    @Test
    void maintenanceLocksBlockConcurrentWriteAndLeaveRuntimeTermsConsistent() throws Exception {
        var dataSource = dataSource();
        resetTo(dataSource, "22");
        var jdbc = new JdbcTemplate(dataSource);
        var profileId = UUID.randomUUID();
        var now = Timestamp.from(Instant.parse("2026-07-14T12:00:00Z"));
        insertProfile(jdbc, profileId, "Concurrent Profile", "concurrent@example.test", "oldterm", now);
        jdbc.execute("""
                CREATE FUNCTION profile.block_search_term_backfill() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(65120);
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER block_search_term_backfill
                BEFORE INSERT ON profile.search_terms
                FOR EACH ROW EXECUTE FUNCTION profile.block_search_term_backfill()
                """);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
             var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, BLOCKING_ADVISORY_LOCK);
                lock.execute();
            }
            var migration = executor.submit(() -> configuration(dataSource)
                    .target(MigrationVersion.fromVersion("23")).load().migrate());
            assertTrue(awaitMigrationTableLock(jdbc), "migration never acquired its maintenance table locks");

            var writer = executor.submit(() -> {
                writeUpdatedTerms(dataSource, profileId, now);
                return null;
            });
            assertTrue(awaitWriterBlocked(jdbc), "source writer never blocked on the maintenance lock");
            assertFalse(writer.isDone(), "source write was not blocked by the migration maintenance locks");

            blocker.commit();
            migration.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
        }

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM profile.search_terms WHERE profile_id=? AND term='oldterm'",
                Integer.class, profileId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM profile.search_terms WHERE profile_id=? AND term='newterm'",
                Integer.class, profileId));
    }

    private static void writeUpdatedTerms(DataSource dataSource, UUID profileId, Timestamp now) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var applicationName = connection.prepareStatement(
                    "SET application_name = 'search-term-migration-writer'");
                 var update = connection.prepareStatement(
                    "UPDATE profile.profiles SET summary='newterm', updated_at=? WHERE id=?");
                 var delete = connection.prepareStatement("DELETE FROM profile.search_terms WHERE profile_id=?");
                 var insert = connection.prepareStatement("""
                         INSERT INTO profile.search_terms (profile_id, field_key, term, weight)
                         VALUES (?, 'profile.summary', 'newterm', 3)
                         """)) {
                applicationName.execute();
                update.setTimestamp(1, now);
                update.setObject(2, profileId);
                update.executeUpdate();
                delete.setObject(1, profileId);
                delete.executeUpdate();
                insert.setObject(1, profileId);
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private static boolean awaitMigrationTableLock(JdbcTemplate jdbc) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_locks lock
                        WHERE lock.relation = 'profile.profiles'::regclass
                          AND lock.mode = 'ShareRowExclusiveLock'
                          AND lock.granted
                    )
                    """, Boolean.class))) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean awaitWriterBlocked(JdbcTemplate jdbc) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE application_name = 'search-term-migration-writer'
                          AND wait_event_type = 'Lock'
                    )
                    """, Boolean.class))) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void insertProfile(JdbcTemplate jdbc, UUID id, String name, String email,
                                      String summary, Timestamp now) {
        jdbc.update("""
                INSERT INTO profile.profiles (id, full_name, email, summary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, name, email, summary, now, now);
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void resetTo(DataSource dataSource, String target) {
        configuration(dataSource).cleanDisabled(false).load().clean();
        configuration(dataSource).target(MigrationVersion.fromVersion(target)).load().migrate();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configuration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .defaultSchema("profile").schemas("profile", "document", "job_schema", "match");
    }
}
