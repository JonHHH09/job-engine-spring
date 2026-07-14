package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileResumeDocumentRepository;
import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Owns transactional retention and cleanup for private generated-resume assets. */
@Service
public class GeneratedResumeAssetService {

    private final ProfileRepository profileRepository;
    private final ProfileResumeDocumentRepository resumeDocumentRepository;
    private final DocumentRepository documentRepository;
    private final GeneratedResumeFileRepository fileRepository;
    private final TransactionLifecycle transactionLifecycle;
    private final GeneratedResumeCleanupService cleanupService;

    public GeneratedResumeAssetService(
            ProfileRepository profileRepository,
            ProfileResumeDocumentRepository resumeDocumentRepository,
            DocumentRepository documentRepository,
            GeneratedResumeFileRepository fileRepository,
            TransactionLifecycle transactionLifecycle,
            GeneratedResumeCleanupService cleanupService
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.resumeDocumentRepository = Objects.requireNonNull(resumeDocumentRepository, "resumeDocumentRepository must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
    }

    ProfileResumeDocumentRepository.Replacement replace(ProfileResumeDocument resumeDocument) {
        return replaceWithinTransaction(resumeDocument);
    }

    @Transactional
    public ProfileResumeDocumentRepository.Replacement replace(
            ProfileResumeDocument resumeDocument,
            String generatedFilePath
    ) {
        deleteGeneratedFileOnRollback(generatedFilePath);
        return replaceWithinTransaction(resumeDocument);
    }

    private ProfileResumeDocumentRepository.Replacement replaceWithinTransaction(ProfileResumeDocument resumeDocument) {
        ProfileResumeDocumentRepository.Replacement replacement = resumeDocumentRepository.replace(resumeDocument);
        replacement.previous()
                .filter(previous -> !previous.documentId().equals(replacement.saved().documentId()))
                .ifPresent(this::deleteUnreferencedAfterReplacement);
        return replacement;
    }

    public boolean deleteProfile(UUID profileId) {
        var privateResumeLinks = resumeDocumentRepository.lockAndFindAllByProfileId(profileId);
        boolean deleted = profileRepository.deleteProfile(profileId);
        if (deleted) {
            privateResumeLinks.forEach(this::deleteUnreferencedAfterReplacement);
        }
        return deleted;
    }

    void deleteGeneratedFileOnRollback(String filePath) {
        transactionLifecycle.afterRollback(() -> fileRepository.deleteIfExists(filePath));
    }

    void discardFailedGeneratedFile(String filePath) {
        fileRepository.deleteIfExists(filePath);
    }

    void discardFailedGeneratedAsset(UUID documentId, String filePath) {
        documentRepository.deleteFileIfUnreferenced(documentId);
        fileRepository.deleteIfExists(filePath);
    }

    private void deleteUnreferencedAfterReplacement(ProfileResumeDocument previous) {
        documentRepository.deleteFileIfUnreferenced(previous.documentId());
        cleanupService.enqueueAfterCommit(previous.filePath());
    }
}
