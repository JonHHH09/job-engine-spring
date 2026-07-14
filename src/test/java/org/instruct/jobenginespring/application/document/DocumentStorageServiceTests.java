package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.DocumentStorageService.ExtractStoredPdfTextRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoreDocumentFileRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentStorageServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-03T17:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private final InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
    private final PdfTextExtractionService pdfTextExtractionService = mock(PdfTextExtractionService.class);
    private final DocumentStorageService service = new DocumentStorageService(repository, pdfTextExtractionService, CLOCK);

    @Test
    void rejectsStoredDocumentImportOutsideConfiguredRoot() throws IOException {
        Path importRoot = tempDir.resolve("imports");
        Files.createDirectories(importRoot);
        Path outsideRoot = writePdfLikeFile("outside.pdf", "outside root");
        DocumentStorageService rootedService = new DocumentStorageService(
                repository,
                pdfTextExtractionService,
                importRoot.toString()
        );

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> rootedService.storeDocumentFile(new StoreDocumentFileRequest(outsideRoot.toString(), null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("file must be under configured import root", exception.details().get("reason"));
    }


    @Test
    void storesPdfFileAsMetadataWithoutExposingContent() throws IOException {
        Path pdf = writePdfLikeFile("resume.pdf", "private fixture text");

        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));

        assertEquals("resume.pdf", metadata.originalFileName());
        assertEquals(DocumentStorageService.PDF_MEDIA_TYPE, metadata.mediaType());
        assertEquals(Files.size(pdf), metadata.byteSize());
        assertEquals(64, metadata.sha256().length());
        assertEquals(NOW, metadata.createdAt());
        assertEquals(NOW, metadata.updatedAt());
        assertTrue(repository.findFileContentById(metadata.id()).isPresent());
    }

    @Test
    void storesDistinctDocumentsForDuplicateContentAndDeduplicatesBlobIdentity() throws IOException {
        Path first = writePdfLikeFile("first.pdf", "same text");
        Path second = writePdfLikeFile("second.pdf", "same text");

        StoredDocumentMetadata firstMetadata = service.storeDocumentFile(new StoreDocumentFileRequest(first.toString(), null));
        StoredDocumentMetadata secondMetadata = service.storeDocumentFile(new StoreDocumentFileRequest(second.toString(), null));

        assertFalse(firstMetadata.id().equals(secondMetadata.id()));
        assertEquals(firstMetadata.sha256(), secondMetadata.sha256());
        assertEquals("first.pdf", firstMetadata.originalFileName());
        assertEquals("second.pdf", secondMetadata.originalFileName());
        assertEquals(2, repository.fileCount());
        assertEquals(1, repository.blobCount());
    }

    @Test
    void rejectsNonPdfWhenMediaTypeIsPdf() throws IOException {
        Path notPdf = tempDir.resolve("not.pdf");
        Files.writeString(notPdf, "not a pdf");

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.storeDocumentFile(new StoreDocumentFileRequest(notPdf.toString(), null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("file must be a PDF document", exception.details().get("reason"));
    }

    @Test
    void extractsStoredPdfAndPersistsBoundedExtractionWhenRequested() throws IOException {
        Path pdf = writePdfLikeFile("stored.pdf", "placeholder");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));
        PdfTextExtractionResult extraction = new PdfTextExtractionResult(
                "stored.pdf",
                1,
                12,
                false,
                "sample text",
                List.of(new PdfTextExtractionService.ExtractedPdfPage(1, "sample text"))
        );
        when(pdfTextExtractionService.extractText(any(StoredDocumentFile.class), any(), any())).thenReturn(extraction);

        StoredPdfTextExtractionResult result = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 1_000, true, true)
        );

        assertEquals(metadata, result.document());
        assertNotNull(result.extractionId());
        assertEquals(extraction, result.extraction());
        assertEquals(1, repository.extractionCount());
        PdfExtractionRecord savedExtraction = repository.lastExtraction();
        assertEquals(metadata.id(), savedExtraction.fileId());
        assertEquals("sample text", savedExtraction.extractedText());
        verify(pdfTextExtractionService).extractText(
                any(StoredDocumentFile.class),
                eq(PdfTextExtractionService.MAX_CHARACTERS_LIMIT),
                eq(true)
        );
    }

    @Test
    void reusesExistingPersistedExtractionForSameFile() throws IOException {
        Path pdf = writePdfLikeFile("stored-once.pdf", "placeholder");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));
        PdfTextExtractionResult extraction = new PdfTextExtractionResult("stored-once.pdf", 1, 4, false, "text", List.of());
        when(pdfTextExtractionService.extractText(any(StoredDocumentFile.class), any(), any())).thenReturn(extraction);

        StoredPdfTextExtractionResult first = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 1_000, false, true)
        );
        StoredPdfTextExtractionResult second = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 1_000, false, true)
        );

        assertEquals(first.extractionId(), second.extractionId());
        assertEquals("text", second.extraction().text());
        assertEquals(1, repository.extractionCount());
        verify(pdfTextExtractionService).extractText(any(StoredDocumentFile.class), any(), any());
    }

    @Test
    void persistedExtractionUsesCanonicalLimitAndAppliesLargerRequestViews() throws IOException {
        Path pdf = writePdfLikeFile("canonical.pdf", "placeholder");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));
        PdfTextExtractionResult canonical = new PdfTextExtractionResult(
                "canonical.pdf",
                1,
                20,
                false,
                "0123456789abcdefghij",
                List.of()
        );
        when(pdfTextExtractionService.extractText(
                any(StoredDocumentFile.class),
                eq(PdfTextExtractionService.MAX_CHARACTERS_LIMIT),
                eq(true)
        )).thenReturn(canonical);

        StoredPdfTextExtractionResult first = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 5, false, true)
        );
        StoredPdfTextExtractionResult second = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 15, false, true)
        );

        assertEquals("01234", first.extraction().text());
        assertTrue(first.extraction().truncated());
        assertEquals("0123456789abcde", second.extraction().text());
        assertTrue(second.extraction().truncated());
        assertEquals(20, repository.lastExtraction().characterCount());
        assertEquals("0123456789abcdefghij", repository.lastExtraction().extractedText());
        verify(pdfTextExtractionService, times(1)).extractText(any(StoredDocumentFile.class), any(), any());
    }

    @Test
    void cachedAndNewPersistedResponsesHonorIncludePages() throws IOException {
        Path pdf = writePdfLikeFile("pages.pdf", "placeholder");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));
        PdfTextExtractionResult pageProjection = new PdfTextExtractionResult(
                "pages.pdf",
                2,
                13,
                false,
                "first\n\nsecond",
                List.of(
                        new PdfTextExtractionService.ExtractedPdfPage(1, "first"),
                        new PdfTextExtractionService.ExtractedPdfPage(2, "second")
                )
        );
        when(pdfTextExtractionService.extractText(
                any(StoredDocumentFile.class),
                eq(PdfTextExtractionService.MAX_CHARACTERS_LIMIT),
                eq(true)
        )).thenReturn(pageProjection);
        StoredPdfTextExtractionResult first = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 13, true, true)
        );
        StoredPdfTextExtractionResult cachedDefault = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 13, null, true)
        );
        StoredPdfTextExtractionResult explicitCached = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 13, true, true)
        );
        StoredPdfTextExtractionResult explicitOmission = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 13, false, true)
        );

        assertEquals(pageProjection, first.extraction());
        assertEquals(pageProjection, cachedDefault.extraction());
        assertEquals(pageProjection, explicitCached.extraction());
        assertEquals(List.of(), explicitOmission.extraction().pages());
        assertEquals(first.extractionId(), cachedDefault.extractionId());
        assertEquals(cachedDefault.extractionId(), explicitCached.extractionId());
        verify(pdfTextExtractionService).extractText(
                any(StoredDocumentFile.class),
                eq(PdfTextExtractionService.MAX_CHARACTERS_LIMIT),
                eq(true)
        );
        verify(pdfTextExtractionService, times(1)).extractText(any(StoredDocumentFile.class), any(), any());
    }

    @Test
    void nonPersistedExtractionPreservesOmittedIncludePages() throws IOException {
        Path pdf = writePdfLikeFile("non-persisted-pages.pdf", "placeholder");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));
        PdfTextExtractionResult extraction = new PdfTextExtractionResult(
                "non-persisted-pages.pdf",
                1,
                4,
                false,
                "text",
                List.of(new PdfTextExtractionService.ExtractedPdfPage(1, "text"))
        );
        when(pdfTextExtractionService.extractText(any(StoredDocumentFile.class), eq(1_000), eq(null)))
                .thenReturn(extraction);

        StoredPdfTextExtractionResult result = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 1_000, null, false)
        );

        assertEquals(extraction, result.extraction());
        verify(pdfTextExtractionService).extractText(any(StoredDocumentFile.class), eq(1_000), eq(null));
    }

    @Test
    void refreshesLegacyPersistedExtractionWithoutChangingItsIdentity() throws IOException {
        Path pdf = writePdfLikeFile("legacy.pdf", "placeholder");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));
        UUID extractionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        repository.savePdfExtraction(new PdfExtractionRecord(
                extractionId,
                metadata.id(),
                "spring-ai-page-pdf-document-reader",
                5,
                1,
                true,
                "short",
                NOW
        ));
        PdfTextExtractionResult canonical = new PdfTextExtractionResult(
                "legacy.pdf", 1, 11, false, "longer text", List.of()
        );
        when(pdfTextExtractionService.extractText(
                any(StoredDocumentFile.class),
                eq(PdfTextExtractionService.MAX_CHARACTERS_LIMIT),
                eq(true)
        )).thenReturn(canonical);

        StoredPdfTextExtractionResult result = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 11, false, true)
        );

        assertEquals(extractionId, result.extractionId());
        assertEquals("longer text", result.extraction().text());
        assertEquals("spring-ai-page-pdf-document-reader:canonical-250000-pages-v2", repository.lastExtraction().extractor());
        assertEquals(1, repository.extractionCount());
    }

    @Test
    void reportsMissingStoredDocumentSafely() {
        UUID missingId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.getDocumentMetadata(missingId));

        assertEquals("not_found", exception.errorCode().code());
        assertEquals("Stored document was not found", exception.safeMessage());
    }

    @Test
    void getsStoredDocumentMetadata() throws IOException {
        Path pdf = writePdfLikeFile("metadata.pdf", "metadata text");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));

        assertEquals(metadata, service.getDocumentMetadata(metadata.id()));
    }

    @Test
    void canStoreGenericNonPdfMediaTypeWithoutPdfHeaderValidation() throws IOException {
        Path text = tempDir.resolve("notes.txt");
        Files.writeString(text, "plain text fixture");

        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(text.toString(), " Text/Plain "));
        Path pdf = writePdfLikeFile("default-media.pdf", "default media");
        StoredDocumentMetadata blankMediaTypeMetadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), " "));

        assertEquals("notes.txt", metadata.originalFileName());
        assertEquals("text/plain", metadata.mediaType());
        assertEquals(DocumentStorageService.PDF_MEDIA_TYPE, blankMediaTypeMetadata.mediaType());
        assertEquals(2, repository.fileCount());
    }

    @Test
    void rejectsBlankMissingDirectoryAndInvalidPaths() {
        assertValidationReason("must not be blank", () -> service.storeDocumentFile(new StoreDocumentFileRequest(" ", null)));
        assertValidationReason("must not be blank", () -> service.storeDocumentFile(new StoreDocumentFileRequest(null, null)));
        assertValidationReason("file was not found", () -> service.storeDocumentFile(new StoreDocumentFileRequest(tempDir.resolve("missing.pdf").toString(), null)));
        assertValidationReason("must identify a regular file", () -> service.storeDocumentFile(new StoreDocumentFileRequest(tempDir.toString(), null)));
        assertValidationReason("must be a valid file path", () -> service.storeDocumentFile(new StoreDocumentFileRequest("bad\0path", null)));
    }

    @Test
    void rejectsEmptyOversizedAndShortPdfFiles() throws IOException {
        Path empty = tempDir.resolve("empty.pdf");
        Files.write(empty, new byte[0]);
        assertValidationReason("file must not be empty", () -> service.storeDocumentFile(new StoreDocumentFileRequest(empty.toString(), null)));

        Path shortPdf = tempDir.resolve("short.pdf");
        Files.writeString(shortPdf, "%PD");
        assertValidationReason("file must be a PDF document", () -> service.storeDocumentFile(new StoreDocumentFileRequest(shortPdf.toString(), null)));

        Path oversized = tempDir.resolve("oversized.pdf");
        byte[] oversizedContent = new byte[(int) PdfTextExtractionService.MAX_FILE_BYTES + 1];
        System.arraycopy(new byte[]{'%', 'P', 'D', 'F', '-'}, 0, oversizedContent, 0, 5);
        Files.write(oversized, oversizedContent);
        assertValidationReason(
                "file size exceeds limit of " + PdfTextExtractionService.MAX_FILE_BYTES + " bytes",
                () -> service.storeDocumentFile(new StoreDocumentFileRequest(oversized.toString(), null))
        );
    }

    @Test
    void extractStoredPdfTextCanSkipPersistence() throws IOException {
        Path pdf = writePdfLikeFile("stored-no-persist.pdf", "placeholder");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(pdf.toString(), null));
        PdfTextExtractionResult extraction = new PdfTextExtractionResult("stored-no-persist.pdf", 1, 4, false, "text", List.of());
        when(pdfTextExtractionService.extractText(any(StoredDocumentFile.class), any(), any())).thenReturn(extraction);

        StoredPdfTextExtractionResult resultWithNullFlag = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 1_000, false, null)
        );
        StoredPdfTextExtractionResult resultWithFalseFlag = service.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(metadata.id(), 1_000, false, false)
        );

        assertEquals(extraction, resultWithNullFlag.extraction());
        assertEquals(extraction, resultWithFalseFlag.extraction());
        assertEquals(0, repository.extractionCount());
    }

    @Test
    void rejectsExtractionForMissingOrNonPdfStoredDocuments() throws IOException {
        UUID missingId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertEquals("not_found", assertThrows(
                ApplicationException.class,
                () -> service.extractStoredPdfText(new ExtractStoredPdfTextRequest(missingId, null, null, null))
        ).errorCode().code());

        Path text = tempDir.resolve("notes.txt");
        Files.writeString(text, "plain text fixture");
        StoredDocumentMetadata metadata = service.storeDocumentFile(new StoreDocumentFileRequest(text.toString(), "text/plain"));

        assertValidationReason(
                "stored document must have media type application/pdf",
                () -> service.extractStoredPdfText(new ExtractStoredPdfTextRequest(metadata.id(), null, null, null))
        );
    }

    @Test
    void rejectsNullRequestsAndDocumentIds() {
        assertThrows(NullPointerException.class, () -> service.storeDocumentFile(null));
        assertThrows(NullPointerException.class, () -> service.extractStoredPdfText(null));
        assertThrows(NullPointerException.class, () -> service.getDocumentMetadata(null));
        assertThrows(NullPointerException.class, () -> service.extractStoredPdfText(new ExtractStoredPdfTextRequest(null, null, null, null)));
    }

    private static void assertValidationReason(String expectedReason, ThrowingRunnable operation) {
        ApplicationException exception = assertThrows(ApplicationException.class, operation::run);
        assertEquals("validation_error", exception.errorCode().code());
        assertEquals(expectedReason, exception.details().get("reason"));
    }

    private Path writePdfLikeFile(String fileName, String text) throws IOException {
        Path pdf = tempDir.resolve(fileName);
        Files.writeString(pdf, "%PDF-1.3\n" + text);
        return pdf;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class InMemoryDocumentRepository implements DocumentRepository {

        private final java.util.Map<UUID, StoredDocumentFile> files = new java.util.LinkedHashMap<>();
        private final java.util.Set<String> blobs = new java.util.LinkedHashSet<>();
        private final java.util.List<PdfExtractionRecord> extractions = new java.util.ArrayList<>();

        @Override
        public StoredDocumentMetadata saveFile(StoredDocumentFile file) {
            blobs.add(file.sha256());
            files.put(file.id(), file);
            return file.metadata();
        }

        @Override
        public Optional<StoredDocumentMetadata> findFileMetadataById(UUID fileId) {
            return Optional.ofNullable(files.get(fileId)).map(StoredDocumentFile::metadata);
        }

        @Override
        public Optional<StoredDocumentFile> findFileContentById(UUID fileId) {
            return Optional.ofNullable(files.get(fileId));
        }

        @Override
        public Optional<PdfExtractionRecord> findPdfExtractionByFileId(UUID fileId) {
            return extractions.stream()
                    .filter(extraction -> extraction.fileId().equals(fileId))
                    .findFirst();
        }

        @Override
        public PdfExtractionRecord savePdfExtraction(PdfExtractionRecord extraction) {
            extractions.add(extraction);
            return extraction;
        }

        @Override
        public PdfExtractionRecord updatePdfExtraction(PdfExtractionRecord extraction) {
            extractions.replaceAll(existing -> existing.id().equals(extraction.id()) ? extraction : existing);
            return extraction;
        }

        @Override
        public boolean deleteFileIfUnreferenced(UUID fileId) {
            return files.remove(fileId) != null;
        }

        private int fileCount() {
            return files.size();
        }

        private int blobCount() {
            return blobs.size();
        }

        private int extractionCount() {
            return extractions.size();
        }

        private PdfExtractionRecord lastExtraction() {
            return extractions.getLast();
        }
    }
}
