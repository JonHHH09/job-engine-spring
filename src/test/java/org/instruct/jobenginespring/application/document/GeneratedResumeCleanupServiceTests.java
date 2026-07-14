package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedResumeCleanupServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final GeneratedResumeCleanupRepository cleanupRepository = mock(GeneratedResumeCleanupRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final GeneratedResumeFileRepository fileRepository = mock(GeneratedResumeFileRepository.class);
    private final TransactionLifecycle transactionLifecycle = mock(TransactionLifecycle.class);
    private GeneratedResumeCleanupService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedResumeCleanupService(
                cleanupRepository,
                documentRepository,
                fileRepository,
                transactionLifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void durableTaskIsCreatedBeforeCommitAndCompletedAfterSuccessfulDeletion() {
        when(cleanupRepository.enqueue("old.pdf", NOW)).thenReturn(TASK_ID);
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("old.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("old.pdf")).thenReturn(true);

        assertEquals(TASK_ID, service.enqueueAfterCommit("old.pdf"));

        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactionLifecycle).afterCommit(callback.capture());
        callback.getValue().run();

        verify(fileRepository).deleteIfExists("old.pdf");
        verify(cleanupRepository).markCompleted(TASK_ID, NOW);
    }

    @Test
    void afterCommitDeletionFailureIsRecordedForRetryAndNeverEscapes() {
        when(cleanupRepository.enqueue("old.pdf", NOW)).thenReturn(TASK_ID);
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("old.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("old.pdf")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("filesystem unavailable"))
                .when(fileRepository).deleteIfExists("old.pdf");

        service.enqueueAfterCommit("old.pdf");
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactionLifecycle).afterCommit(callback.capture());

        assertDoesNotThrow(() -> callback.getValue().run());
        verify(cleanupRepository).markPending(
                TASK_ID,
                NOW.plus(GeneratedResumeCleanupService.RETRY_DELAY),
                IllegalStateException.class.getSimpleName()
        );
    }

    @Test
    void referencedPathIsCompletedWithPreservation() {
        when(cleanupRepository.enqueue("referenced.pdf", NOW)).thenReturn(TASK_ID);
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("referenced.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("referenced.pdf")).thenReturn(false);

        service.enqueueAfterCommit("referenced.pdf");
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactionLifecycle).afterCommit(callback.capture());
        callback.getValue().run();

        verify(fileRepository, never()).deleteIfExists("referenced.pdf");
        verify(cleanupRepository).markCompleted(TASK_ID, NOW);
    }

    @Test
    void durableStateFailureInAfterCommitCallbackNeverEscapes() {
        when(cleanupRepository.enqueue("old.pdf", NOW)).thenReturn(TASK_ID);
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.enqueueAfterCommit("old.pdf");
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactionLifecycle).afterCommit(callback.capture());

        assertDoesNotThrow(() -> callback.getValue().run());
    }

    @Test
    void retriesDueTasksAndIgnoresTasksClaimedByAnotherWorker() {
        UUID claimedElsewhere = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(cleanupRepository.findDueTaskIds(NOW, GeneratedResumeCleanupService.RETRY_BATCH_SIZE))
                .thenReturn(List.of(TASK_ID, claimedElsewhere));
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("old.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("old.pdf")).thenReturn(true);
        when(cleanupRepository.claim(claimedElsewhere, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.empty());

        service.retryDueTasks();

        verify(fileRepository).deleteIfExists("old.pdf");
        verify(cleanupRepository).markCompleted(TASK_ID, NOW);
    }

    @Test
    void compensationWaitsForCompletionThenCreatesAndAttemptsDurableTask() {
        when(cleanupRepository.enqueue("compensation.pdf", NOW)).thenReturn(TASK_ID);
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("compensation.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("compensation.pdf")).thenReturn(true);

        service.enqueueAfterCompletion("compensation.pdf");

        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(transactionLifecycle).afterCompletion(completion.capture());
        completion.getValue().run();
        verify(cleanupRepository).enqueue("compensation.pdf", NOW);
        verify(fileRepository).deleteIfExists("compensation.pdf");
        verify(cleanupRepository).markCompleted(TASK_ID, NOW);
    }

    @Test
    void preparationFailureLeavesTaskReclaimableAndRetryCompletes() {
        when(cleanupRepository.findDueTaskIds(NOW, GeneratedResumeCleanupService.RETRY_BATCH_SIZE))
                .thenReturn(List.of(TASK_ID));
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(Optional.of("old.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("old.pdf")).thenReturn(true);

        assertDoesNotThrow(service::retryDueTasks);
        service.retryDueTasks();

        verify(fileRepository).deleteIfExists("old.pdf");
        verify(cleanupRepository).markCompleted(TASK_ID, NOW);
    }

    @Test
    void filesystemFailureMarksPendingAndRetryRepeatsIdempotentDeletion() {
        when(cleanupRepository.findDueTaskIds(NOW, GeneratedResumeCleanupService.RETRY_BATCH_SIZE))
                .thenReturn(List.of(TASK_ID));
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("old.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("old.pdf")).thenReturn(true);
        doThrow(new IllegalStateException("filesystem unavailable"))
                .doNothing()
                .when(fileRepository).deleteIfExists("old.pdf");

        service.retryDueTasks();
        service.retryDueTasks();

        verify(fileRepository, times(2)).deleteIfExists("old.pdf");
        verify(cleanupRepository).markPending(
                TASK_ID,
                NOW.plus(GeneratedResumeCleanupService.RETRY_DELAY),
                IllegalStateException.class.getSimpleName()
        );
        verify(cleanupRepository).markCompleted(TASK_ID, NOW);
    }

    @Test
    void completionFailureMovesTaskBackToPendingAndRetryCompletes() {
        when(cleanupRepository.findDueTaskIds(NOW, GeneratedResumeCleanupService.RETRY_BATCH_SIZE))
                .thenReturn(List.of(TASK_ID));
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("old.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("old.pdf")).thenReturn(true);
        doThrow(new IllegalStateException("completion unavailable"))
                .doNothing()
                .when(cleanupRepository).markCompleted(TASK_ID, NOW);

        service.retryDueTasks();
        service.retryDueTasks();

        verify(fileRepository, times(2)).deleteIfExists("old.pdf");
        verify(cleanupRepository).markPending(
                TASK_ID,
                NOW.plus(GeneratedResumeCleanupService.RETRY_DELAY),
                IllegalStateException.class.getSimpleName()
        );
        verify(cleanupRepository, times(2)).markCompleted(TASK_ID, NOW);
    }

    @Test
    void pendingStateFailureDoesNotEscapeAndLeaseAllowsLaterReclaim() {
        when(cleanupRepository.findDueTaskIds(NOW, GeneratedResumeCleanupService.RETRY_BATCH_SIZE))
                .thenReturn(List.of(TASK_ID));
        when(cleanupRepository.claim(TASK_ID, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.of("old.pdf"));
        when(documentRepository.prepareGeneratedFileCleanup("old.pdf")).thenReturn(true);
        doThrow(new IllegalStateException("filesystem unavailable"))
                .when(fileRepository).deleteIfExists("old.pdf");
        doThrow(new IllegalStateException("database unavailable"))
                .when(cleanupRepository).markPending(
                        TASK_ID,
                        NOW.plus(GeneratedResumeCleanupService.RETRY_DELAY),
                        IllegalStateException.class.getSimpleName()
                );

        assertDoesNotThrow(service::retryDueTasks);
    }
}
