package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Commits the durable outcome of a generated-resume filesystem cleanup attempt. */
@Service
public class GeneratedResumeCleanupFinalizer {

    private final GeneratedResumeCleanupRepository cleanupRepository;

    public GeneratedResumeCleanupFinalizer(GeneratedResumeCleanupRepository cleanupRepository) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID taskId, Instant completedAt) {
        cleanupRepository.markCompleted(taskId, completedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPending(UUID taskId, Instant nextAttemptAt, String failureType) {
        cleanupRepository.markPending(taskId, nextAttemptAt, failureType);
    }
}
