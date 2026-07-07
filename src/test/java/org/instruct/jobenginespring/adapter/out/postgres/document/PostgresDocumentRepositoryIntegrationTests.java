package org.instruct.jobenginespring.adapter.out.postgres.document;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresDocumentRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-03T17:30:00Z");
    private static final UUID FILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EXTRACTION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final byte[] PDF_CONTENT = "%PDF-1.3\nfixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;

    private PostgresDocumentRepository repository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE document.documents, document.blobs CASCADE");
        repository = new PostgresDocumentRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    @Test
    void flywayCreatesDocumentStorageTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'document'
                ORDER BY table_name
                """, String.class);

        assertEquals(List.of("blobs", "documents", "files", "pdf_extractions"), tables);
    }

    @Test
    void savesAndFindsFileMetadataAndContent() {
        StoredDocumentFile file = sampleFile(SHA256, PDF_CONTENT);

        StoredDocumentMetadata saved = repository.saveFile(file);

        assertEquals(file.metadata(), saved);
        assertEquals(saved, repository.findFileMetadataById(FILE_ID).orElseThrow());
        assertEquals(saved, repository.findFileMetadataBySha256(SHA256).orElseThrow());
        StoredDocumentFile found = repository.findFileContentById(FILE_ID).orElseThrow();
        assertEquals(file.metadata(), found.metadata());
        assertArrayEquals(PDF_CONTENT, found.content());
    }

    @Test
    void duplicateSha256ReusesBlobButStoresDistinctDocumentMetadata() {
        StoredDocumentFile existing = sampleFile(SHA256, PDF_CONTENT);
        StoredDocumentFile duplicate = new StoredDocumentFile(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                "duplicate.pdf",
                "application/pdf",
                PDF_CONTENT.length,
                SHA256,
                PDF_CONTENT,
                NOW,
                NOW
        );

        StoredDocumentMetadata first = repository.saveFile(existing);
        StoredDocumentMetadata second = repository.saveFile(duplicate);

        assertFalse(first.id().equals(second.id()));
        assertEquals(first.sha256(), second.sha256());
        assertEquals("sample.pdf", first.originalFileName());
        assertEquals("duplicate.pdf", second.originalFileName());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document.blobs", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM document.documents", Integer.class));
    }

    @Test
    void duplicatePrimaryKeyWithoutMatchingSha256RethrowsConstraintFailure() {
        repository.saveFile(sampleFile(SHA256, PDF_CONTENT));
        StoredDocumentFile conflictingId = sampleFile(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "%PDF-1.3\nother".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> repository.saveFile(conflictingId));
    }

    @Test
    void savesPdfExtractionAndCascadesWithFileDelete() {
        repository.saveFile(sampleFile(SHA256, PDF_CONTENT));
        PdfExtractionRecord extraction = new PdfExtractionRecord(
                EXTRACTION_ID,
                FILE_ID,
                "test-extractor",
                11,
                1,
                false,
                "sample text",
                NOW
        );

        PdfExtractionRecord saved = repository.savePdfExtraction(extraction);

        assertEquals(extraction, saved);
        assertEquals(extraction, repository.findPdfExtractionByFileId(FILE_ID).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document.pdf_extractions", Integer.class));
        jdbc.update("DELETE FROM document.documents WHERE id = ?", FILE_ID);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document.pdf_extractions", Integer.class));
    }

    @Test
    void enforcesOnePdfExtractionPerFile() {
        repository.saveFile(sampleFile(SHA256, PDF_CONTENT));
        repository.savePdfExtraction(new PdfExtractionRecord(
                EXTRACTION_ID,
                FILE_ID,
                "test-extractor",
                11,
                1,
                false,
                "sample text",
                NOW
        ));
        PdfExtractionRecord duplicate = new PdfExtractionRecord(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                FILE_ID,
                "test-extractor",
                11,
                1,
                false,
                "sample text",
                NOW
        );

        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> repository.savePdfExtraction(duplicate));
    }

    @Test
    void reportsMissingDocumentRows() {
        UUID missingId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        assertFalse(repository.findFileMetadataById(missingId).isPresent());
        assertFalse(repository.findFileContentById(missingId).isPresent());
    }

    private static StoredDocumentFile sampleFile(String sha256, byte[] content) {
        return new StoredDocumentFile(
                FILE_ID,
                "sample.pdf",
                "application/pdf",
                content.length,
                sha256,
                content,
                NOW,
                NOW
        );
    }
}
