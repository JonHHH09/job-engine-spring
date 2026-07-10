package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileResumeDocumentRepository;
import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GeneratedResumeAssetServiceTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OLD_DOCUMENT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID NEW_DOCUMENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final ProfileResumeDocumentRepository resumeDocumentRepository = mock(ProfileResumeDocumentRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final GeneratedResumeFileRepository fileRepository = mock(GeneratedResumeFileRepository.class);
    private final TransactionLifecycle transactionLifecycle = mock(TransactionLifecycle.class);
    private final GeneratedResumeCleanupService cleanupService = mock(GeneratedResumeCleanupService.class);
    private GeneratedResumeAssetService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedResumeAssetService(
                profileRepository,
                resumeDocumentRepository,
                documentRepository,
                fileRepository,
                transactionLifecycle,
                cleanupService
        );
    }

    @Test
    void replacementDeletesPrivateDocumentInTransactionAndOldFileAfterCommit() {
        ProfileResumeDocument previous = link(OLD_DOCUMENT_ID, "old.pdf");
        ProfileResumeDocument saved = link(NEW_DOCUMENT_ID, "new.pdf");
        var replacement = new ProfileResumeDocumentRepository.Replacement(saved, Optional.of(previous));
        when(resumeDocumentRepository.replace(saved)).thenReturn(replacement);
        when(documentRepository.deleteFileIfUnreferenced(OLD_DOCUMENT_ID)).thenReturn(true);

        assertSame(replacement, service.replace(saved));

        verify(documentRepository).deleteFileIfUnreferenced(OLD_DOCUMENT_ID);
        verify(cleanupService).enqueueAfterCommit("old.pdf");
        verifyNoInteractions(fileRepository);
    }

    @Test
    void replacementPreservesStillReferencedOrUnchangedAssets() {
        ProfileResumeDocument unchanged = link(OLD_DOCUMENT_ID, "same.pdf");
        when(resumeDocumentRepository.replace(unchanged)).thenReturn(
                new ProfileResumeDocumentRepository.Replacement(unchanged, Optional.of(unchanged))
        );

        service.replace(unchanged);

        verifyNoInteractions(documentRepository, fileRepository, transactionLifecycle, cleanupService);

        ProfileResumeDocument previous = link(OLD_DOCUMENT_ID, "old.pdf");
        ProfileResumeDocument saved = link(NEW_DOCUMENT_ID, "new.pdf");
        when(resumeDocumentRepository.replace(saved)).thenReturn(
                new ProfileResumeDocumentRepository.Replacement(saved, Optional.of(previous))
        );
        when(documentRepository.deleteFileIfUnreferenced(OLD_DOCUMENT_ID)).thenReturn(false);

        service.replace(saved);

        verify(documentRepository).deleteFileIfUnreferenced(OLD_DOCUMENT_ID);
        verifyNoInteractions(cleanupService);
    }

    @Test
    void profileDeletionCleansCapturedResumeAssetsOnlyWhenProfileWasDeleted() {
        ProfileResumeDocument master = link(OLD_DOCUMENT_ID, "master.pdf");
        ProfileResumeDocument canadian = new ProfileResumeDocument(
                UUID.randomUUID(), PROFILE_ID, NEW_DOCUMENT_ID, "canadian.pdf", "canadian_resume", NOW, NOW
        );
        when(resumeDocumentRepository.lockAndFindAllByProfileId(PROFILE_ID)).thenReturn(List.of(master, canadian));
        when(profileRepository.deleteProfile(PROFILE_ID)).thenReturn(true);
        when(documentRepository.deleteFileIfUnreferenced(OLD_DOCUMENT_ID)).thenReturn(true);
        when(documentRepository.deleteFileIfUnreferenced(NEW_DOCUMENT_ID)).thenReturn(false);

        assertTrue(service.deleteProfile(PROFILE_ID));

        verify(documentRepository).deleteFileIfUnreferenced(OLD_DOCUMENT_ID);
        verify(documentRepository).deleteFileIfUnreferenced(NEW_DOCUMENT_ID);
        verify(cleanupService).enqueueAfterCommit("master.pdf");

        UUID missing = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        when(resumeDocumentRepository.lockAndFindAllByProfileId(missing)).thenReturn(List.of(master));
        when(profileRepository.deleteProfile(missing)).thenReturn(false);

        assertFalse(service.deleteProfile(missing));
        verify(documentRepository, org.mockito.Mockito.times(1)).deleteFileIfUnreferenced(OLD_DOCUMENT_ID);
        verify(documentRepository, org.mockito.Mockito.times(1)).deleteFileIfUnreferenced(NEW_DOCUMENT_ID);
    }

    @Test
    void registersRollbackCleanupAndCanDiscardFailedFileImmediately() {
        service.deleteGeneratedFileOnRollback("rollback.pdf");
        ArgumentCaptor<Runnable> rollback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactionLifecycle).afterRollback(rollback.capture());
        rollback.getValue().run();
        verify(fileRepository).deleteIfExists("rollback.pdf");

        service.discardFailedGeneratedFile("failed.pdf");
        verify(fileRepository).deleteIfExists("failed.pdf");
    }

    private static ProfileResumeDocument link(UUID documentId, String path) {
        return new ProfileResumeDocument(
                UUID.randomUUID(), PROFILE_ID, documentId, path, "master_resume", NOW, NOW
        );
    }
}
