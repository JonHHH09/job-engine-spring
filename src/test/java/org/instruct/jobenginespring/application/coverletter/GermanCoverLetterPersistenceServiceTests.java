package org.instruct.jobenginespring.application.coverletter;

import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.GeneratedResumeCleanupService;
import org.instruct.jobenginespring.application.document.GermanCoverLetterPersistenceService;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.domain.coverletter.CoverLetter;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterParagraph;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GermanCoverLetterPersistenceServiceTests {

    @Test
    void replacementRegistersRollbackAndCleansPreviousVariantAfterCommit() {
        CoverLetterRepository repository = mock(CoverLetterRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        GeneratedResumeCleanupService cleanup = mock(GeneratedResumeCleanupService.class);
        TransactionLifecycle transactions = mock(TransactionLifecycle.class);
        GermanCoverLetterPersistenceService service = new GermanCoverLetterPersistenceService(repository, documents, cleanup, transactions);
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        CoverLetter parent = parent(now);
        CoverLetterVariant variant = variant(parent.id(), now, "new.pdf");
        CoverLetterRepository.CoverLetterAggregateWrite write = new CoverLetterRepository.CoverLetterAggregateWrite(
                parent,
                new CoverLetterRepository.VariantWrite(variant, List.of(new CoverLetterParagraph(UUID.randomUUID(), variant.id(), 0, "Paragraph")))
        );
        CoverLetterVariant previous = variant(parent.id(), now, "old.pdf");
        CoverLetterRepository.ReplaceResult replaced = new CoverLetterRepository.ReplaceResult(parent, variant, List.of(previous));
        when(repository.replace(write)).thenReturn(replaced);

        assertSame(replaced, service.replace(write, new GermanCoverLetterPersistenceService.GeneratedAsset(variant.documentId(), "new.pdf")));

        ArgumentCaptor<Runnable> rollback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactions).afterRollback(rollback.capture());
        rollback.getValue().run();
        verify(cleanup).enqueueNow(variant.documentId(), "new.pdf");
        verify(documents).deleteFileIfUnreferenced(previous.documentId());
        verify(cleanup).enqueueAfterCommit(previous.documentId(), "old.pdf");
    }

    @Test
    void validatesCollaboratorsAndInputs() {
        CoverLetterRepository repository = mock(CoverLetterRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        GeneratedResumeCleanupService cleanup = mock(GeneratedResumeCleanupService.class);
        TransactionLifecycle transactions = mock(TransactionLifecycle.class);
        assertThrows(NullPointerException.class, () -> new GermanCoverLetterPersistenceService(null, documents, cleanup, transactions));
        assertThrows(NullPointerException.class, () -> new GermanCoverLetterPersistenceService(repository, null, cleanup, transactions));
        assertThrows(NullPointerException.class, () -> new GermanCoverLetterPersistenceService(repository, documents, null, transactions));
        assertThrows(NullPointerException.class, () -> new GermanCoverLetterPersistenceService(repository, documents, cleanup, null));
        GermanCoverLetterPersistenceService service = new GermanCoverLetterPersistenceService(repository, documents, cleanup, transactions);
        assertThrows(NullPointerException.class, () -> service.replace(null, null));
        assertThrows(NullPointerException.class, () -> service.cleanupDeletedVariants(null));
    }

    @Test
    void capturesAndCleansAssetsAfterProfileOrJobSourceDeletion() {
        CoverLetterRepository repository = mock(CoverLetterRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        GeneratedResumeCleanupService cleanup = mock(GeneratedResumeCleanupService.class);
        GermanCoverLetterPersistenceService service = new GermanCoverLetterPersistenceService(
                repository, documents, cleanup, mock(TransactionLifecycle.class)
        );
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        CoverLetter parent = parent(now);
        CoverLetterVariant variant = variant(parent.id(), now, "deleted.pdf");

        when(repository.lockAndFindAllByProfileId(parent.profileId())).thenReturn(List.of(variant));
        when(repository.lockAndFindAllByJobId(parent.jobId())).thenReturn(List.of(variant));

        assertEquals(List.of(variant), service.lockAndFindAllByProfileId(parent.profileId()));
        assertEquals(List.of(variant), service.lockAndFindAllByJobId(parent.jobId()));
        service.cleanupDeletedVariants(List.of(variant));

        verify(documents).deleteFileIfUnreferenced(variant.documentId());
        verify(cleanup).enqueueAfterCommit(variant.documentId(), "deleted.pdf");
    }

    private static CoverLetter parent(Instant now) {
        return new CoverLetter(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now, now, now, now);
    }

    private static CoverLetterVariant variant(UUID parentId, Instant now, String path) {
        return new CoverLetterVariant(UUID.randomUUID(), parentId, "germany", "de", UUID.randomUUID(), path,
                "Subject", "Salutation", "Closing", "Signature", now, now);
    }
}
