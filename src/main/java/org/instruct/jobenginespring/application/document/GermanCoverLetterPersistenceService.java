package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.CoverLetterAggregateWrite;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.ReplaceResult;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.List;
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
        cleanupDeletedVariants(replaced.previousVariants());
        return replaced;
    }

    /** Captures generated assets before a profile's cascading foreign keys delete their rows. */
    public List<CoverLetterVariant> lockAndFindAllByProfileId(UUID profileId) {
        return coverLetterRepository.lockAndFindAllByProfileId(Objects.requireNonNull(profileId, "profileId must not be null"));
    }

    /** Captures generated assets before a job's cascading foreign keys delete their rows. */
    public List<CoverLetterVariant> lockAndFindAllByJobId(UUID jobId) {
        return coverLetterRepository.lockAndFindAllByJobId(Objects.requireNonNull(jobId, "jobId must not be null"));
    }

    /** Removes document metadata and schedules filesystem cleanup after a source cascade has completed. */
    public void cleanupDeletedVariants(List<CoverLetterVariant> variants) {
        List.copyOf(Objects.requireNonNull(variants, "variants must not be null")).forEach(previous -> {
            documentRepository.deleteFileIfUnreferenced(previous.documentId());
            cleanupService.enqueueAfterCommit(previous.documentId(), previous.filePath());
        });
    }

    public record GeneratedAsset(UUID documentId, String filePath) {
        public GeneratedAsset {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(filePath, "filePath must not be null");
        }
    }
}
