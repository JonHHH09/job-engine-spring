package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfilePdfExternalWorkTransactionBoundaryTests {

    @Test
    void storedPdfParsingStartsWithoutAnActiveTransaction() {
        DocumentStorageService documents = mock(DocumentStorageService.class);
        when(documents.extractStoredPdfText(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new BoundaryReached();
        });
        ProfilePdfIngestionService service = transactionalProxy(new ProfilePdfIngestionService(
                documents,
                mock(ProfileTextExtractor.class),
                mock(ProfilePdfSourceRepository.class),
                mock(ProfilePdfIngestionPersistenceService.class)
        ), ProfilePdfIngestionService.class);

        assertThrows(BoundaryReached.class, () -> service.ingestProfileFromStoredPdf(
                new ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest(UUID.randomUUID(), null, null, null)
        ));
    }

    @Test
    void profileExtractionProviderRunsWithoutAnActiveTransaction() {
        UUID documentId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-14T12:00:00Z");
        DocumentStorageService documents = mock(DocumentStorageService.class);
        when(documents.extractStoredPdfText(any())).thenReturn(new StoredPdfTextExtractionResult(
                new StoredDocumentMetadata(documentId, "resume.pdf", "application/pdf", 10, "sha", now, now),
                extractionId,
                new PdfTextExtractionResult("resume.pdf", 1, 4, false, "text", List.of())
        ));
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        when(sources.findByPdfExtractionId(extractionId)).thenReturn(Optional.empty());
        when(sources.findByDocumentSha256("sha")).thenReturn(Optional.empty());
        ProfileTextExtractor extractor = input -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new BoundaryReached();
        };
        ProfilePdfIngestionService service = transactionalProxy(new ProfilePdfIngestionService(
                documents, extractor, sources, mock(ProfilePdfIngestionPersistenceService.class)
        ), ProfilePdfIngestionService.class);

        assertThrows(BoundaryReached.class, () -> service.ingestProfileFromStoredPdf(
                new ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest(documentId, null, null, null)
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

    private static final class BoundaryReached extends RuntimeException {
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
