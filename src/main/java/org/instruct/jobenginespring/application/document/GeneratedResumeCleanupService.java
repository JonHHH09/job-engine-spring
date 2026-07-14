package org.instruct.jobenginespring.application.document;

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

    private final GeneratedResumeCleanupRepository cleanupRepository;
    private final TransactionLifecycle transactionLifecycle;
    private final GeneratedResumeCleanupExecutor cleanupExecutor;
    private final Duration completedRetention;
    private final int retentionBatchSize;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            @Value("${job-engine.pdf-generation.cleanup-completed-retention:30d}") Duration completedRetention,
            @Value("${job-engine.pdf-generation.cleanup-retention-batch-size:1000}") int retentionBatchSize
    ) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor must not be null");
        this.completedRetention = requirePositive(completedRetention, "completedRetention");
        if (retentionBatchSize <= 0) {
            throw new IllegalArgumentException("retentionBatchSize must be positive");
        }
        this.retentionBatchSize = retentionBatchSize;
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            Clock clock
    ) {
        this(
                cleanupRepository,
                transactionLifecycle,
                cleanupExecutor,
                COMPLETED_RETENTION,
                RETENTION_BATCH_SIZE
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            Clock clock,
            Duration completedRetention,
            int retentionBatchSize
    ) {
        this(cleanupRepository, transactionLifecycle, cleanupExecutor, completedRetention, retentionBatchSize);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            GeneratedResumeFileRepository fileRepository,
            TransactionLifecycle transactionLifecycle,
            Clock clock
    ) {
        this(
                cleanupRepository,
                transactionLifecycle,
                new GeneratedResumeCleanupExecutor(cleanupRepository, fileRepository, clock),
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

    public void retryDueTasks() {
        cleanupRepository.findDueTaskIds(clock.instant(), GeneratedResumeCleanupExecutor.RETRY_BATCH_SIZE)
                .forEach(cleanupExecutor::attemptSafely);
    }

    public int purgeCompletedTasks() {
        return cleanupRepository.deleteCompletedBefore(clock.instant().minus(completedRetention), retentionBatchSize);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
