package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Creates compensation cleanup intent independently from the transaction whose outcome was uncertain. */
@Service
public class GeneratedResumeCleanupTaskCreator {

    private final GeneratedResumeCleanupRepository cleanupRepository;

    public GeneratedResumeCleanupTaskCreator(GeneratedResumeCleanupRepository cleanupRepository) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID enqueue(String filePath, Instant createdAt) {
        return cleanupRepository.enqueue(
                Objects.requireNonNull(filePath, "filePath must not be null"),
                Objects.requireNonNull(createdAt, "createdAt must not be null")
        );
    }
}
