package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.DocumentStorageService.StoreDocumentFileRequest;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratePdfFileRequest;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileResumeDocumentRepository;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProfileResumePdfGenerationWorkflow {

    private final ProfileRepository profileRepository;
    private final DocumentStorageService documentStorageService;
    private final ProfileResumeDocumentRepository profileResumeDocumentRepository;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public ProfileResumePdfGenerationWorkflow(
            ProfileRepository profileRepository,
            DocumentStorageService documentStorageService,
            ProfileResumeDocumentRepository profileResumeDocumentRepository
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.documentStorageService = Objects.requireNonNull(documentStorageService, "documentStorageService must not be null");
        this.profileResumeDocumentRepository = Objects.requireNonNull(profileResumeDocumentRepository, "profileResumeDocumentRepository must not be null");
    }

    ProfileResumePdfGenerationWorkflow(
            ProfileRepository profileRepository,
            DocumentStorageService documentStorageService,
            ProfileResumeDocumentRepository profileResumeDocumentRepository,
            Clock clock
    ) {
        this(profileRepository, documentStorageService, profileResumeDocumentRepository);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    ProfileAggregate requireProfile(UUID profileId) {
        UUID safeProfileId = validateProfileId(profileId);
        return profileRepository.findProfileAggregate(safeProfileId)
                .orElseThrow(() -> new ProfileNotFoundException(safeProfileId));
    }

    GeneratedProfileResumePdf generateAndLink(GenerateProfileResumePdfCommand command) {
        GenerateProfileResumePdfCommand safeCommand = Objects.requireNonNull(command, "command must not be null");
        UUID profileId = validateProfileId(safeCommand.profileId());
        String resumeType = validateResumeType(safeCommand.resumeType());
        Path outputDirectory = Objects.requireNonNull(safeCommand.outputDirectory(), "outputDirectory must not be null");
        Optional<ProfileResumeDocument> existingLink = profileResumeDocumentRepository.findByProfileIdAndResumeType(profileId, resumeType);

        GeneratedPdfFileResult generatedFile = new PdfGenerationService(outputDirectory).generatePdfFile(new GeneratePdfFileRequest(
                safeCommand.fileName(),
                safeCommand.title(),
                safeCommand.body()
        ));
        StoredDocumentMetadata document = documentStorageService.storeDocumentFile(new StoreDocumentFileRequest(
                generatedFile.path(),
                DocumentStorageService.PDF_MEDIA_TYPE
        ));
        Instant now = clock.instant();
        ProfileResumeDocument savedLink = profileResumeDocumentRepository.save(new ProfileResumeDocument(
                existingLink.map(ProfileResumeDocument::id).orElseGet(UUID::randomUUID),
                profileId,
                document.id(),
                generatedFile.path(),
                resumeType,
                existingLink.map(ProfileResumeDocument::createdAt).orElse(now),
                now
        ));

        return new GeneratedProfileResumePdf(
                profileId,
                savedLink.id(),
                savedLink.documentId(),
                savedLink.filePath(),
                savedLink.resumeType(),
                document,
                generatedFile,
                existingLink.isPresent(),
                savedLink.updatedAt().toString()
        );
    }

    private static UUID validateProfileId(UUID profileId) {
        if (profileId == null) {
            throw validation("profileId", "must not be null");
        }
        return profileId;
    }

    private static String validateResumeType(String resumeType) {
        if (resumeType == null || resumeType.isBlank()) {
            throw validation("resumeType", "must not be blank");
        }
        return resumeType.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid resume PDF generation request",
                Map.of("field", field, "reason", reason),
                null
        );
    }

    record GenerateProfileResumePdfCommand(
            UUID profileId,
            String resumeType,
            Path outputDirectory,
            String fileName,
            String title,
            String body
    ) {
    }

    public record GeneratedProfileResumePdf(
            UUID profileId,
            UUID resumeLinkId,
            UUID documentId,
            String filePath,
            String resumeType,
            StoredDocumentMetadata document,
            GeneratedPdfFileResult generatedFile,
            boolean replacedExistingLink,
            String linkedAt
    ) {
    }
}
