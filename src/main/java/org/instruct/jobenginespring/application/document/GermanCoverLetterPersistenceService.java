package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.CoverLetterAggregateWrite;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.ReplaceResult;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Owns the short database transaction that atomically replaces one German cover-letter variant. */
@Service
public class GermanCoverLetterPersistenceService {

    private final CoverLetterRepository coverLetterRepository;
    private final DocumentRepository documentRepository;
    private final GeneratedResumeCleanupService cleanupService;
    private final TransactionLifecycle transactionLifecycle;

    public GermanCoverLetterPersistenceService(
            CoverLetterRepository coverLetterRepository,
            DocumentRepository documentRepository,
            GeneratedResumeCleanupService cleanupService,
            TransactionLifecycle transactionLifecycle
    ) {
        this.coverLetterRepository = Objects.requireNonNull(coverLetterRepository, "coverLetterRepository must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
    }

    @Transactional
    public ReplaceResult replace(CoverLetterAggregateWrite write, GeneratedAsset generatedAsset) {
        Objects.requireNonNull(write, "write must not be null");
        GeneratedAsset asset = Objects.requireNonNull(generatedAsset, "generatedAsset must not be null");
        transactionLifecycle.afterRollback(() -> cleanupService.enqueueNow(asset.documentId(), asset.filePath()));

        ReplaceResult replaced = coverLetterRepository.replace(write);
        replaced.previousVariants().forEach(previous -> {
            documentRepository.deleteFileIfUnreferenced(previous.documentId());
            cleanupService.enqueueAfterCommit(previous.documentId(), previous.filePath());
        });
        return replaced;
    }

    public record GeneratedAsset(UUID documentId, String filePath) {
        public GeneratedAsset {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(filePath, "filePath must not be null");
        }
    }
}
