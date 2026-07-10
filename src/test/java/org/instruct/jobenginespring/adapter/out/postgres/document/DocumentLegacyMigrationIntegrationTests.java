package org.instruct.jobenginespring.adapter.out.postgres.document;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class DocumentLegacyMigrationIntegrationTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    @Test
    void migratesLegacyFilesAndDropsTheDuplicateBinaryTable() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema")
                .target("6")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
        UUID documentId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        byte[] content = "%PDF-1.7\nlegacy document".getBytes(StandardCharsets.UTF_8);
        String sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Instant now = Instant.parse("2026-07-09T12:00:00Z");
        jdbc.update("""
                        INSERT INTO document.files (
                            id, original_file_name, media_type, byte_size, sha256, content, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                documentId,
                "legacy.pdf",
                "application/pdf",
                content.length,
                sha256,
                content,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema")
                .load()
                .migrate();

        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'document' AND table_name = 'files'
                """, Integer.class));
        assertEquals("legacy.pdf", jdbc.queryForObject(
                "SELECT original_file_name FROM document.documents WHERE id = ?",
                String.class,
                documentId
        ));
        assertArrayEquals(content, jdbc.queryForObject("""
                SELECT blob.content
                FROM document.documents stored_document
                JOIN document.blobs blob ON blob.id = stored_document.blob_id
                WHERE stored_document.id = ?
                """, byte[].class, documentId));
    }
}
