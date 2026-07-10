package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persists filesystem-cleanup intent in the surrounding database transaction. */
@Service
public class GeneratedResumeCleanupService {

    static final java.time.Duration CLAIM_LEASE = GeneratedResumeCleanupExecutor.CLAIM_LEASE;
    static final java.time.Duration RETRY_DELAY = GeneratedResumeCleanupExecutor.RETRY_DELAY;
    static final int RETRY_BATCH_SIZE = GeneratedResumeCleanupExecutor.RETRY_BATCH_SIZE;

    private final GeneratedResumeCleanupRepository cleanupRepository;
    private final TransactionLifecycle transactionLifecycle;
    private final GeneratedResumeCleanupExecutor cleanupExecutor;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor
    ) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor must not be null");
    }

    GeneratedResumeCleanupService(
            GeneratedResumeCleanupRepository cleanupRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupExecutor cleanupExecutor,
            Clock clock
    ) {
        this(cleanupRepository, transactionLifecycle, cleanupExecutor);
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
}
