package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persists filesystem-cleanup intent in the surrounding database transaction. */
@Service
public class GeneratedResumeCleanupService {

    static final java.time.Duration CLAIM_LEASE = GeneratedResumeCleanupExecutor.CLAIM_LEASE;
    static final java.time.Duration RETRY_DELAY = GeneratedResumeCleanupExecutor.RETRY_DELAY;
    static final int RETRY_BATCH_SIZE = GeneratedResumeCleanupExecutor.RETRY_BATCH_SIZE;
    static final Duration COMPLETED_RETENTION = Duration.ofDays(30);
    static final int RETENTION_BATCH_SIZE = 1_000;
    static final int RETENTION_MAX_BATCHES_PER_RUN = 10;

    private final GeneratedResumeCleanupRepository cleanupRepository;
    private final TransactionLifecycle transactionLifecycle;
    private final GeneratedResumeCleanupExecutor cleanupExecutor;
    private final GeneratedResumeCleanupTaskCreator taskCreator;
    private final Duration completedRetention;
    private final int retentionBatchSize;
    private final int retentionMaxBatchesPerRun;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            GeneratedResumeCleanupTaskCreator taskCreator,
            @Value("${job-engine.pdf-generation.cleanup-completed-retention:30d}") Duration completedRetention,
            @Value("${job-engine.pdf-generation.cleanup-retention-batch-size:1000}") int retentionBatchSize,
            @Value("${job-engine.pdf-generation.cleanup-retention-max-batches-per-run:10}")
            int retentionMaxBatchesPerRun
    ) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor must not be null");
        this.taskCreator = Objects.requireNonNull(taskCreator, "taskCreator must not be null");
        this.completedRetention = requirePositive(completedRetention, "completedRetention");
        if (retentionBatchSize <= 0) {
            throw new IllegalArgumentException("retentionBatchSize must be positive");
        }
        this.retentionBatchSize = retentionBatchSize;
        if (retentionMaxBatchesPerRun <= 0) {
            throw new IllegalArgumentException("retentionMaxBatchesPerRun must be positive");
        }
        this.retentionMaxBatchesPerRun = retentionMaxBatchesPerRun;
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            GeneratedResumeCleanupTaskCreator taskCreator,
            Clock clock
    ) {
        this(
                cleanupRepository,
                transactionLifecycle,
                cleanupExecutor,
                taskCreator,
                COMPLETED_RETENTION,
                RETENTION_BATCH_SIZE,
                RETENTION_MAX_BATCHES_PER_RUN
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            Duration completedRetention,
            int retentionBatchSize,
            int retentionMaxBatchesPerRun
    ) {
        this(
                cleanupRepository,
                transactionLifecycle,
                cleanupExecutor,
                new GeneratedResumeCleanupTaskCreator(cleanupRepository),
                completedRetention,
                retentionBatchSize,
                retentionMaxBatchesPerRun
        );
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            Clock clock,
            Duration completedRetention,
            int retentionBatchSize,
            int retentionMaxBatchesPerRun
    ) {
        this(
                cleanupRepository,
                transactionLifecycle,
                cleanupExecutor,
                new GeneratedResumeCleanupTaskCreator(cleanupRepository),
                completedRetention,
                retentionBatchSize,
                retentionMaxBatchesPerRun
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            DocumentRepository documentRepository,
            GeneratedResumeFileRepository fileRepository,
            TransactionLifecycle transactionLifecycle,
            Clock clock
    ) {
        this(
                cleanupRepository,
                transactionLifecycle,
                new GeneratedResumeCleanupExecutor(
                        new GeneratedResumeCleanupPreparation(cleanupRepository, documentRepository, clock),
                        new GeneratedResumeCleanupFileDeletion(fileRepository),
                        new GeneratedResumeCleanupFinalizer(cleanupRepository),
                        clock
                ),
                new GeneratedResumeCleanupTaskCreator(cleanupRepository),
                clock
        );
    }

    public UUID enqueueAfterCommit(String filePath) {
        Instant now = clock.instant();
        UUID taskId = cleanupRepository.enqueue(
                Objects.requireNonNull(filePath, "filePath must not be null"),
                now
        );
        transactionLifecycle.afterCommit(() -> cleanupExecutor.attemptSafely(taskId));
        return taskId;
    }

    public UUID enqueueAfterCommit(UUID documentId, String filePath) {
        Instant now = clock.instant();
        UUID taskId = cleanupRepository.enqueue(
                documentId,
                Objects.requireNonNull(filePath, "filePath must not be null"),
                now
        );
        transactionLifecycle.afterCommit(() -> cleanupExecutor.attemptSafely(taskId));
        return taskId;
    }

    public void enqueueAfterCompletion(String filePath) {
        String safeFilePath = Objects.requireNonNull(filePath, "filePath must not be null");
        transactionLifecycle.afterCompletion(() -> enqueueNow(safeFilePath));
    }

    public void enqueueAfterCompletion(UUID documentId, String filePath) {
        String safeFilePath = Objects.requireNonNull(filePath, "filePath must not be null");
        transactionLifecycle.afterCompletion(() -> enqueueNow(documentId, safeFilePath));
    }

    public UUID enqueueNow(String filePath) {
        UUID taskId = taskCreator.enqueue(
                Objects.requireNonNull(filePath, "filePath must not be null"),
                clock.instant()
        );
        cleanupExecutor.attemptSafely(taskId);
        return taskId;
    }

    public UUID enqueueNow(UUID documentId, String filePath) {
        UUID taskId = taskCreator.enqueue(
                documentId,
                Objects.requireNonNull(filePath, "filePath must not be null"),
                clock.instant()
        );
        cleanupExecutor.attemptSafely(taskId);
        return taskId;
    }

    public void retryDueTasks() {
        cleanupRepository.findDueTaskIds(clock.instant(), GeneratedResumeCleanupExecutor.RETRY_BATCH_SIZE)
                .forEach(cleanupExecutor::attemptSafely);
    }

    public long purgeCompletedTasks() {
        var cutoff = clock.instant().minus(completedRetention);
        long totalDeleted = 0;
        for (int batch = 0; batch < retentionMaxBatchesPerRun; batch++) {
            int deleted = cleanupRepository.deleteCompletedBefore(cutoff, retentionBatchSize);
            if (deleted < 0 || deleted > retentionBatchSize) {
                throw new IllegalStateException("repository returned an invalid retention deletion count");
            }
            totalDeleted = Math.addExact(totalDeleted, deleted);
            if (deleted < retentionBatchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
