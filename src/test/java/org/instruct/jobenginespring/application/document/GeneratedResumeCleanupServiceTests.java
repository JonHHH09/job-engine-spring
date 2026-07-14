package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedResumeCleanupServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final GeneratedResumeCleanupRepository cleanupRepository = mock(GeneratedResumeCleanupRepository.class);
    private final GeneratedResumeFileRepository fileRepository = mock(GeneratedResumeFileRepository.class);
    private final TransactionLifecycle transactionLifecycle = mock(TransactionLifecycle.class);
    private GeneratedResumeCleanupService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedResumeCleanupService(
                cleanupRepository,
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
        org.mockito.Mockito.doThrow(new IllegalStateException("filesystem unavailable"))
                .when(fileRepository).deleteIfExists("old.pdf");

        service.enqueueAfterCommit("old.pdf");
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(transactionLifecycle).afterCommit(callback.capture());

        assertDoesNotThrow(() -> callback.getValue().run());
        verify(cleanupRepository).markPending(
                TASK_ID,
                NOW.plus(GeneratedResumeCleanupService.RETRY_DELAY),
                GeneratedResumeCleanupExecutor.FILE_DELETE_FAILED
        );
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
        when(cleanupRepository.claim(claimedElsewhere, NOW, NOW.plus(GeneratedResumeCleanupService.CLAIM_LEASE)))
                .thenReturn(Optional.empty());

        service.retryDueTasks();

        verify(fileRepository).deleteIfExists("old.pdf");
        verify(cleanupRepository).markCompleted(TASK_ID, NOW);
    }

    @Test
    void drainsCompletedTasksUntilAShortBatch() {
        when(cleanupRepository.deleteCompletedBefore(
                NOW.minus(GeneratedResumeCleanupService.COMPLETED_RETENTION),
                GeneratedResumeCleanupService.RETENTION_BATCH_SIZE
        )).thenReturn(
                GeneratedResumeCleanupService.RETENTION_BATCH_SIZE,
                GeneratedResumeCleanupService.RETENTION_BATCH_SIZE,
                17
        );

        assertEquals(2_017, service.purgeCompletedTasks());

        verify(cleanupRepository, times(3)).deleteCompletedBefore(
                NOW.minus(GeneratedResumeCleanupService.COMPLETED_RETENTION),
                GeneratedResumeCleanupService.RETENTION_BATCH_SIZE
        );
    }

    @Test
    void boundsFullBatchDrainingByConfiguredMaximum() {
        var boundedService = serviceWithRetention(Duration.ofDays(1), 2, 3);
        when(cleanupRepository.deleteCompletedBefore(NOW.minus(Duration.ofDays(1)), 2)).thenReturn(2);

        assertEquals(6, boundedService.purgeCompletedTasks());

        verify(cleanupRepository, times(3)).deleteCompletedBefore(NOW.minus(Duration.ofDays(1)), 2);
    }

    @Test
    void stopsAfterAnEmptyBatchWithoutLooping() {
        var boundedService = serviceWithRetention(Duration.ofDays(1), 2, Integer.MAX_VALUE);

        assertEquals(0, boundedService.purgeCompletedTasks());

        verify(cleanupRepository).deleteCompletedBefore(NOW.minus(Duration.ofDays(1)), 2);
    }

    @Test
    void totalsMoreThanIntegerMaxValueWithoutOverflow() {
        var boundedService = serviceWithRetention(Duration.ofDays(1), Integer.MAX_VALUE, 2);
        when(cleanupRepository.deleteCompletedBefore(
                NOW.minus(Duration.ofDays(1)), Integer.MAX_VALUE
        )).thenReturn(Integer.MAX_VALUE);

        assertEquals(4_294_967_294L, boundedService.purgeCompletedTasks());
    }

    @Test
    void rejectsInvalidRepositoryDeletionCounts() {
        var boundedService = serviceWithRetention(Duration.ofDays(1), 10, 1);
        when(cleanupRepository.deleteCompletedBefore(NOW.minus(Duration.ofDays(1)), 10)).thenReturn(11);

        assertThrows(IllegalStateException.class, boundedService::purgeCompletedTasks);

        var negativeCountService = serviceWithRetention(Duration.ofDays(2), 10, 1);
        when(cleanupRepository.deleteCompletedBefore(NOW.minus(Duration.ofDays(2)), 10)).thenReturn(-1);

        assertThrows(IllegalStateException.class, negativeCountService::purgeCompletedTasks);
    }

    @Test
    void acceptsStrictlyPositiveRetentionBoundariesWithoutMovingCutoffIntoFuture() {
        var boundaryService = serviceWithRetention(Duration.ofNanos(1), 1, 1);

        boundaryService.purgeCompletedTasks();

        verify(cleanupRepository).deleteCompletedBefore(NOW.minusNanos(1), 1);
    }

    @Test
    void rejectsNonPositiveConfiguredCompletedRetentionAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> configuredService(Duration.ZERO, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> configuredService(Duration.ofNanos(-1), 1, 1));
    }

    @Test
    void rejectsNonPositiveConfiguredRetentionBatchSizeAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> configuredService(Duration.ofDays(1), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> configuredService(Duration.ofDays(1), -1, 1));
    }

    @Test
    void rejectsNonPositiveConfiguredRetentionMaxBatchesPerRunAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> configuredService(Duration.ofDays(1), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> configuredService(Duration.ofDays(1), 1, -1));
    }

    private GeneratedResumeCleanupService serviceWithRetention(Duration retention, int batchSize, int maxBatches) {
        return new GeneratedResumeCleanupService(
                cleanupRepository,
                transactionLifecycle,
                mock(GeneratedResumeCleanupExecutor.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                retention,
                batchSize,
                maxBatches
        );
    }

    private GeneratedResumeCleanupService configuredService(Duration retention, int batchSize, int maxBatches) {
        return new GeneratedResumeCleanupService(
                cleanupRepository,
                transactionLifecycle,
                mock(GeneratedResumeCleanupExecutor.class),
                retention,
                batchSize,
                maxBatches
        );
    }
}
