package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Executes durable cleanup tasks independently from the transaction that requested cleanup. */
@Service
public class GeneratedResumeCleanupExecutor {

    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    static final int RETRY_BATCH_SIZE = 25;

    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedResumeCleanupExecutor.class);

    private final GeneratedResumeCleanupRepository cleanupRepository;
    private final GeneratedResumeFileRepository fileRepository;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GeneratedResumeCleanupExecutor(
            GeneratedResumeCleanupRepository cleanupRepository,
            GeneratedResumeFileRepository fileRepository
    ) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
    }

    GeneratedResumeCleanupExecutor(
            GeneratedResumeCleanupRepository cleanupRepository,
            GeneratedResumeFileRepository fileRepository,
            Clock clock
    ) {
        this(cleanupRepository, fileRepository);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptSafely(UUID taskId) {
        try {
            attempt(taskId);
        } catch (RuntimeException exception) {
            LOGGER.error("Generated resume cleanup task {} could not update its durable state; it will be reclaimed after its lease", taskId, exception);
        }
    }

    private void attempt(UUID taskId) {
        Instant now = clock.instant();
        cleanupRepository.claim(taskId, now, now.plus(CLAIM_LEASE)).ifPresent(filePath -> {
            try {
                fileRepository.deleteIfExists(filePath);
                cleanupRepository.markCompleted(taskId, clock.instant());
            } catch (RuntimeException exception) {
                cleanupRepository.markPending(
                        taskId,
                        clock.instant().plus(RETRY_DELAY),
                        exception.getClass().getSimpleName()
                );
                LOGGER.warn("Generated resume cleanup task {} failed and remains pending for retry", taskId);
            }
        });
    }
}
