package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Claims cleanup work and commits the database-authoritative reference decision. */
@Service
public class GeneratedResumeCleanupPreparation {

    private final GeneratedResumeCleanupRepository cleanupRepository;
    private final DocumentRepository documentRepository;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GeneratedResumeCleanupPreparation(
            GeneratedResumeCleanupRepository cleanupRepository,
            DocumentRepository documentRepository
    ) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
    }

    GeneratedResumeCleanupPreparation(
            GeneratedResumeCleanupRepository cleanupRepository,
            DocumentRepository documentRepository,
            Clock clock
    ) {
        this(cleanupRepository, documentRepository);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PreparedCleanup> prepare(UUID taskId) {
        Instant now = clock.instant();
        return cleanupRepository.claim(taskId, now, now.plus(GeneratedResumeCleanupExecutor.CLAIM_LEASE))
                .map(filePath -> {
                    cleanupRepository.findDocumentId(taskId)
                            .ifPresent(documentRepository::deleteFileIfUnreferenced);
                    return new PreparedCleanup(
                            filePath,
                            documentRepository.prepareGeneratedFileCleanup(filePath)
                    );
                });
    }

    public record PreparedCleanup(String filePath, boolean deleteFile) {

        public PreparedCleanup {
            Objects.requireNonNull(filePath, "filePath must not be null");
        }
    }
}
