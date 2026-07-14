package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentExternalWorkTransactionBoundaryTests {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void fileReadAndHashCompleteBeforeDocumentPersistenceTransaction() throws Exception {
        Path pdf = tempDir.resolve("resume.pdf");
        Files.write(pdf, "%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.saveFile(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0, StoredDocumentFile.class).metadata();
        });
        DocumentStorageService service = transactionalProxy(new DocumentStorageService(
                repository, mock(PdfTextExtractionService.class), tempDir.toString()
        ), DocumentStorageService.class);

        service.storeDocumentFile(new DocumentStorageService.StoreDocumentFileRequest(pdf.toString(), null));
    }

    @Test
    void pdfParsingRunsWithoutAnActiveTransaction() {
        UUID documentId = UUID.randomUUID();
        byte[] content = "%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        StoredDocumentFile file = new StoredDocumentFile(
                documentId, "resume.pdf", DocumentStorageService.PDF_MEDIA_TYPE,
                content.length, "sha", content, NOW, NOW
        );
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.findFileContentById(documentId)).thenReturn(Optional.of(file));
        PdfTextExtractionService extractionService = mock(PdfTextExtractionService.class);
        when(extractionService.extractText(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new PdfTextExtractionService.PdfTextExtractionResult("resume.pdf", 1, 4, false, "text", java.util.List.of());
        });
        DocumentStorageService service = transactionalProxy(new DocumentStorageService(
                repository, extractionService, tempDir.toString()
        ), DocumentStorageService.class);

        service.extractStoredPdfText(new DocumentStorageService.ExtractStoredPdfTextRequest(
                documentId, null, false, false
        ));
    }

    private static <T> T transactionalProxy(T target, Class<T> type) {
        var interceptor = new TransactionInterceptor(
                new TestTransactionManager(),
                new AnnotationTransactionAttributeSource()
        );
        var factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
