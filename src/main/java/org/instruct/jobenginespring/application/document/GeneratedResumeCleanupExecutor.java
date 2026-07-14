package org.instruct.jobenginespring.application.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Executes durable cleanup tasks independently from the transaction that requested cleanup. */
@Service
public class GeneratedResumeCleanupExecutor {

    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    static final int RETRY_BATCH_SIZE = 25;

    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedResumeCleanupExecutor.class);

    private final GeneratedResumeCleanupPreparation preparation;
    private final GeneratedResumeCleanupFileDeletion fileDeletion;
    private final GeneratedResumeCleanupFinalizer finalizer;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GeneratedResumeCleanupExecutor(
            GeneratedResumeCleanupPreparation preparation,
            GeneratedResumeCleanupFileDeletion fileDeletion,
            GeneratedResumeCleanupFinalizer finalizer
    ) {
        this.preparation = Objects.requireNonNull(preparation, "preparation must not be null");
        this.fileDeletion = Objects.requireNonNull(fileDeletion, "fileDeletion must not be null");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer must not be null");
    }

    GeneratedResumeCleanupExecutor(
            GeneratedResumeCleanupPreparation preparation,
            GeneratedResumeCleanupFileDeletion fileDeletion,
            GeneratedResumeCleanupFinalizer finalizer,
            Clock clock
    ) {
        this(preparation, fileDeletion, finalizer);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void attemptSafely(UUID taskId) {
        try {
            attempt(taskId);
        } catch (RuntimeException exception) {
            LOGGER.error("Generated resume cleanup task {} could not update its durable state; it will be reclaimed after its lease", taskId, exception);
        }
    }

    private void attempt(UUID taskId) {
        preparation.prepare(taskId).ifPresent(prepared -> {
            try {
                fileDeletion.deleteIfRequired(prepared);
                finalizer.markCompleted(taskId, clock.instant());
            } catch (RuntimeException exception) {
                finalizer.markPending(
                        taskId,
                        clock.instant().plus(RETRY_DELAY),
                        exception.getClass().getSimpleName()
                );
                LOGGER.warn("Generated resume cleanup task {} failed and remains pending for retry", taskId);
            }
        });
    }
}
