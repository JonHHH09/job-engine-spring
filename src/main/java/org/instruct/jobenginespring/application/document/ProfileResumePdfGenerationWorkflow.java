package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratePdfFileRequest;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.document.PdfGenerationService.PageNumberLocale;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProfileResumePdfGenerationWorkflow {

    private final ProfileRepository profileRepository;
    private final DocumentStorageService documentStorageService;
    private final GeneratedResumeAssetService generatedResumeAssetService;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public ProfileResumePdfGenerationWorkflow(
            ProfileRepository profileRepository,
            DocumentStorageService documentStorageService,
            GeneratedResumeAssetService generatedResumeAssetService
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.documentStorageService = Objects.requireNonNull(documentStorageService, "documentStorageService must not be null");
        this.generatedResumeAssetService = Objects.requireNonNull(generatedResumeAssetService, "generatedResumeAssetService must not be null");
    }

    ProfileResumePdfGenerationWorkflow(
            ProfileRepository profileRepository,
            DocumentStorageService documentStorageService,
            GeneratedResumeAssetService generatedResumeAssetService,
            Clock clock
    ) {
        this(profileRepository, documentStorageService, generatedResumeAssetService);
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

        GeneratedPdfFileResult generatedFile = new PdfGenerationService(outputDirectory).generatePdfFile(
                new GeneratePdfFileRequest(
                        uniqueFileName(safeCommand.fileName()),
                        safeCommand.title(),
                        safeCommand.body()
                ),
                safeCommand.pageNumberLocale()
        );
        StoredDocumentMetadata document = null;
        try {
            document = documentStorageService.storeGeneratedDocumentFile(
                    Path.of(generatedFile.path()),
                    DocumentStorageService.PDF_MEDIA_TYPE
            );
            Instant now = clock.instant();
            ProfileResumeDocumentRepository.Replacement replacement = generatedResumeAssetService.replace(
                    new ProfileResumeDocument(
                            UUID.randomUUID(),
                            profileId,
                            document.id(),
                            generatedFile.path(),
                            resumeType,
                            now,
                            now
                    ),
                    generatedFile.path()
            );
            ProfileResumeDocument savedLink = replacement.saved();

            return new GeneratedProfileResumePdf(
                    profileId,
                    savedLink.id(),
                    savedLink.documentId(),
                    savedLink.filePath(),
                    savedLink.resumeType(),
                    document,
                    generatedFile,
                    replacement.previous().isPresent(),
                    savedLink.updatedAt().toString()
            );
        } catch (RuntimeException | Error failure) {
            try {
                if (document == null) {
                    generatedResumeAssetService.discardFailedGeneratedFile(generatedFile.path());
                } else {
                    generatedResumeAssetService.discardFailedGeneratedAsset(document.id(), generatedFile.path());
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static String uniqueFileName(String requestedFileName) {
        String fileName = requestedFileName == null ? "generated-resume.pdf" : requestedFileName;
        int extensionIndex = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".pdf");
        String baseName = extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
        return baseName + "-" + UUID.randomUUID() + ".pdf";
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
        return resumeType.strip().toLowerCase(Locale.ROOT);
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
            String body,
            PageNumberLocale pageNumberLocale
    ) {
        GenerateProfileResumePdfCommand(
                UUID profileId,
                String resumeType,
                Path outputDirectory,
                String fileName,
                String title,
                String body
        ) {
            this(profileId, resumeType, outputDirectory, fileName, title, body, PageNumberLocale.ENGLISH);
        }
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
