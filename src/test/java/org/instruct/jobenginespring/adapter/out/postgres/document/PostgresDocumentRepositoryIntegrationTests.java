package org.instruct.jobenginespring.adapter.out.postgres.document;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.ExtractStoredPdfTextRequest;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        assertEquals(List.of("blobs", "documents", "generated_resume_file_cleanups", "pdf_extractions"), tables);
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
    void deletesOnlyUnreferencedDocumentAndPreservesSharedBlob() {
        StoredDocumentFile existing = sampleFile(SHA256, PDF_CONTENT);
        UUID duplicateId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        StoredDocumentFile duplicate = new StoredDocumentFile(
                duplicateId,
                "duplicate.pdf",
                "application/pdf",
                PDF_CONTENT.length,
                SHA256,
                PDF_CONTENT,
                NOW,
                NOW
        );
        repository.saveFile(existing);
        repository.saveFile(duplicate);

        assertTrue(repository.deleteFileIfUnreferenced(FILE_ID));

        assertFalse(repository.findFileMetadataById(FILE_ID).isPresent());
        assertTrue(repository.findFileMetadataById(duplicateId).isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document.blobs", Integer.class));
        assertFalse(repository.deleteFileIfUnreferenced(UUID.randomUUID()));
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
    void updatesPdfExtractionInPlace() {
        repository.saveFile(sampleFile(SHA256, PDF_CONTENT));
        repository.savePdfExtraction(new PdfExtractionRecord(
                EXTRACTION_ID, FILE_ID, "legacy", 5, 1, true, "short", NOW
        ));
        Instant refreshedAt = NOW.plusSeconds(60);
        PdfExtractionRecord canonical = new PdfExtractionRecord(
                EXTRACTION_ID,
                FILE_ID,
                "spring-ai-page-pdf-document-reader:canonical-250000-v1",
                11,
                1,
                false,
                "sample text",
                refreshedAt
        );

        PdfExtractionRecord updated = repository.updatePdfExtraction(canonical);

        assertEquals(canonical, updated);
        assertEquals(canonical, repository.findPdfExtractionByFileId(FILE_ID).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document.pdf_extractions", Integer.class));
    }

    @Test
    void persistedCanonicalExtractionIsNotConstrainedByFirstRequestLimit() {
        repository.saveFile(sampleFile(SHA256, PDF_CONTENT));
        PdfTextExtractionService extractionService = mock(PdfTextExtractionService.class);
        PdfTextExtractionResult canonical = new PdfTextExtractionResult(
                "sample.pdf",
                1,
                20,
                false,
                "0123456789abcdefghij",
                List.of()
        );
        when(extractionService.extractText(
                any(byte[].class),
                eq("sample.pdf"),
                eq(PdfTextExtractionService.MAX_CHARACTERS_LIMIT),
                eq(true)
        )).thenReturn(canonical);
        DocumentStorageService service = new DocumentStorageService(repository, extractionService, "/tmp");

        var first = service.extractStoredPdfText(new ExtractStoredPdfTextRequest(FILE_ID, 5, false, true));
        var second = service.extractStoredPdfText(new ExtractStoredPdfTextRequest(FILE_ID, 15, false, true));

        assertEquals("01234", first.extraction().text());
        assertEquals("0123456789abcde", second.extraction().text());
        assertEquals(first.extractionId(), second.extractionId());
        PdfExtractionRecord persisted = repository.findPdfExtractionByFileId(FILE_ID).orElseThrow();
        assertEquals(20, persisted.characterCount());
        assertEquals("0123456789abcdefghij", persisted.extractedText());
        verify(extractionService, times(1)).extractText(any(byte[].class), any(), any(), any());
    }

    @Test
    void simultaneousFirstPersistedExtractionReusesTheWinningRow() throws Exception {
        repository.saveFile(sampleFile(SHA256, PDF_CONTENT));
        PdfTextExtractionService extractionService = mock(PdfTextExtractionService.class);
        PdfTextExtractionResult canonical = new PdfTextExtractionResult(
                "sample.pdf", 1, 11, false, "sample text", List.of()
        );
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier bothExtracted = new CyclicBarrier(2);
        when(extractionService.extractText(
                any(byte[].class),
                eq("sample.pdf"),
                eq(PdfTextExtractionService.MAX_CHARACTERS_LIMIT),
                eq(true)
        )).thenAnswer(invocation -> {
            bothExtracted.await();
            return canonical;
        });
        DocumentStorageService service = new DocumentStorageService(repository, extractionService, "/tmp");

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<DocumentStorageService.StoredPdfTextExtractionResult> first = executor.submit(() -> {
                start.await();
                return service.extractStoredPdfText(new ExtractStoredPdfTextRequest(FILE_ID, 20, false, true));
            });
            Future<DocumentStorageService.StoredPdfTextExtractionResult> second = executor.submit(() -> {
                start.await();
                return service.extractStoredPdfText(new ExtractStoredPdfTextRequest(FILE_ID, 20, false, true));
            });
            start.countDown();

            var firstResult = first.get();
            var secondResult = second.get();

            assertEquals(firstResult.extractionId(), secondResult.extractionId());
            assertEquals("sample text", firstResult.extraction().text());
            assertEquals("sample text", secondResult.extraction().text());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document.pdf_extractions", Integer.class));
            verify(extractionService, times(2)).extractText(any(byte[].class), any(), any(), any());
        }
    }

    @Test
    void savePdfExtractionReusesExistingRowForTheFile() {
        repository.saveFile(sampleFile(SHA256, PDF_CONTENT));
        PdfExtractionRecord existing = new PdfExtractionRecord(
                EXTRACTION_ID,
                FILE_ID,
                "test-extractor",
                11,
                1,
                false,
                "sample text",
                NOW
        );
        repository.savePdfExtraction(existing);
        PdfExtractionRecord duplicate = new PdfExtractionRecord(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                FILE_ID,
                "different-extractor",
                9,
                1,
                true,
                "different",
                NOW.plusSeconds(1)
        );

        assertEquals(existing, repository.savePdfExtraction(duplicate));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document.pdf_extractions", Integer.class));
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
