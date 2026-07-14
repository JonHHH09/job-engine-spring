package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ReplaceResult;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ResumeAggregateWrite;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owns only the short database transaction that atomically replaces a generated German resume. */
@Service
public class GermanResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final DocumentRepository documentRepository;
    private final GeneratedResumeCleanupService cleanupService;
    private final TransactionLifecycle transactionLifecycle;

    public GermanResumePersistenceService(
            ResumeRepository resumeRepository,
            DocumentRepository documentRepository,
            GeneratedResumeCleanupService cleanupService,
            TransactionLifecycle transactionLifecycle
    ) {
        this.resumeRepository = Objects.requireNonNull(resumeRepository, "resumeRepository must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
    }

    @Transactional
    public ReplaceResult replace(ResumeAggregateWrite write, List<GeneratedAsset> generatedAssets) {
        Objects.requireNonNull(write, "write must not be null");
        List<GeneratedAsset> safeAssets = List.copyOf(Objects.requireNonNull(generatedAssets, "generatedAssets must not be null"));
        safeAssets.forEach(asset -> transactionLifecycle.afterRollback(
                () -> cleanupService.enqueueNow(asset.filePath())
        ));

        ReplaceResult replaced = resumeRepository.replaceGermanyResume(write);
        replaced.previousVariants().forEach(previous -> {
            documentRepository.deleteFileIfUnreferenced(previous.documentId());
            cleanupService.enqueueAfterCommit(previous.filePath());
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
