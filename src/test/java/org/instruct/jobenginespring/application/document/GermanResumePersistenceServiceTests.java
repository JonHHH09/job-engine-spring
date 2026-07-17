package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository;
import org.instruct.jobenginespring.application.document.GermanResumePersistenceService.GeneratedAsset;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GermanResumePersistenceServiceTests {

    @Test
    void replacementRegistersRollbackAndSchedulesPreviousAssetsAfterCommit() {
        ResumeRepository resumes = mock(ResumeRepository.class);
        CoverLetterRepository coverLetters = mock(CoverLetterRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        GeneratedResumeCleanupService cleanup = mock(GeneratedResumeCleanupService.class);
        TransactionLifecycle transactions = mock(TransactionLifecycle.class);
        GermanResumePersistenceService service = new GermanResumePersistenceService(
                resumes, coverLetters, documents, cleanup, transactions
        );
        Instant now = Instant.parse("2026-07-14T12:00:00Z");
        Resume resume = new Resume(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Resume.FORMAT_GERMANY, now, now, now, now);
        ResumeRepository.ResumeAggregateWrite write = new ResumeRepository.ResumeAggregateWrite(resume, List.of());
        ResumeVariant previous = new ResumeVariant(
                UUID.randomUUID(), resume.id(), ResumeVariant.LANGUAGE_EN, UUID.randomUUID(), "old.pdf", now, now
        );
        CoverLetterVariant previousCoverLetter = new CoverLetterVariant(
                UUID.randomUUID(), UUID.randomUUID(), "germany", "de", UUID.randomUUID(), "old-letter.pdf",
                "Subject", "Salutation", "Closing", "Signature", now, now
        );
        ResumeRepository.ReplaceResult replaced = new ResumeRepository.ReplaceResult(resume, List.of(), List.of(previous));
        when(coverLetters.deleteByGermanyResumeIdentity(resume.profileId(), resume.jobId()))
                .thenReturn(List.of(previousCoverLetter));
        when(resumes.replaceGermanyResume(write)).thenReturn(replaced);
        GeneratedAsset generated = new GeneratedAsset(UUID.randomUUID(), "new.pdf");

        assertSame(replaced, service.replace(write, List.of(generated)));

        ArgumentCaptor<Runnable> rollback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactions).afterRollback(rollback.capture());
        rollback.getValue().run();
        verify(cleanup).enqueueNow(generated.documentId(), "new.pdf");
        verify(documents).deleteFileIfUnreferenced(previous.documentId());
        verify(cleanup).enqueueAfterCommit(previous.documentId(), "old.pdf");
        verify(documents).deleteFileIfUnreferenced(previousCoverLetter.documentId());
        verify(cleanup).enqueueAfterCommit(previousCoverLetter.documentId(), "old-letter.pdf");
    }

    @Test
    void validatesRequiredCollaboratorsAndInputs() {
        ResumeRepository resumes = mock(ResumeRepository.class);
        CoverLetterRepository coverLetters = mock(CoverLetterRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        GeneratedResumeCleanupService cleanup = mock(GeneratedResumeCleanupService.class);
        TransactionLifecycle transactions = mock(TransactionLifecycle.class);

        assertThrows(NullPointerException.class, () -> new GermanResumePersistenceService(null, coverLetters, documents, cleanup, transactions));
        assertThrows(NullPointerException.class, () -> new GermanResumePersistenceService(resumes, null, documents, cleanup, transactions));
        assertThrows(NullPointerException.class, () -> new GermanResumePersistenceService(resumes, coverLetters, null, cleanup, transactions));
        assertThrows(NullPointerException.class, () -> new GermanResumePersistenceService(resumes, coverLetters, documents, null, transactions));
        assertThrows(NullPointerException.class, () -> new GermanResumePersistenceService(resumes, coverLetters, documents, cleanup, null));

        GermanResumePersistenceService service = new GermanResumePersistenceService(resumes, coverLetters, documents, cleanup, transactions);
        assertThrows(NullPointerException.class, () -> service.replace(null, List.of()));
        assertThrows(NullPointerException.class, () -> service.replace(mock(ResumeRepository.ResumeAggregateWrite.class), null));
        assertThrows(NullPointerException.class, () -> new GeneratedAsset(null, "file.pdf"));
        assertThrows(NullPointerException.class, () -> new GeneratedAsset(UUID.randomUUID(), null));
    }
}
