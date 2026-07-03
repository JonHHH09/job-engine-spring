package org.instruct.jobenginespring.application.profile;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.ExtractStoredPdfTextRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor.ProfileTextExtractionInput;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ProfilePdfIngestionService {

    private static final String SOURCE_TYPE = "resume_pdf";

    @NonNull
    private final DocumentStorageService documentStorageService;
    @NonNull
    private final ProfileTextExtractor profileTextExtractor;
    @NonNull
    private final ProfileService profileService;
    @NonNull
    private final ProfilePdfSourceRepository profilePdfSourceRepository;
    private Clock clock = Clock.systemUTC();

    ProfilePdfIngestionService(
            DocumentStorageService documentStorageService,
            ProfileTextExtractor profileTextExtractor,
            ProfileService profileService,
            ProfilePdfSourceRepository profilePdfSourceRepository,
            Clock clock
    ) {
        this(documentStorageService, profileTextExtractor, profileService, profilePdfSourceRepository);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ProfilePdfIngestionResult ingestProfileFromStoredPdf(IngestProfileFromStoredPdfRequest request) {
        IngestProfileFromStoredPdfRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        UUID documentId = Objects.requireNonNull(safeRequest.documentId(), "documentId must not be null");
        StoredPdfTextExtractionResult storedExtraction = documentStorageService.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(documentId, safeRequest.maxCharacters(), false, true)
        );
        UUID extractionId = Objects.requireNonNull(storedExtraction.extractionId(), "extractionId must not be null");

        return profilePdfSourceRepository.findByPdfExtractionId(extractionId)
                .map(existing -> toResult(existing, storedExtraction, false, true))
                .orElseGet(() -> createOrUpdateLinkedProfile(safeRequest, storedExtraction, extractionId));
    }

    @Transactional(readOnly = true)
    public ProfilePdfSource getProfilePdfSource(UUID profileId) {
        UUID safeProfileId = Objects.requireNonNull(profileId, "profileId must not be null");
        return profilePdfSourceRepository.findByProfileId(safeProfileId)
                .orElseThrow(() -> new ApplicationException(
                        ApplicationErrorCode.NOT_FOUND,
                        "Profile PDF source was not found",
                        Map.of("profileId", String.valueOf(profileId)),
                        null
                ));
    }

    private ProfilePdfIngestionResult createOrUpdateLinkedProfile(
            IngestProfileFromStoredPdfRequest request,
            StoredPdfTextExtractionResult storedExtraction,
            UUID extractionId
    ) {
        UUID existingProfileId = request.existingProfileId();
        if (existingProfileId != null) {
            profilePdfSourceRepository.findByProfileId(existingProfileId)
                    .ifPresent(existing -> {
                        throw new ApplicationException(
                                ApplicationErrorCode.VALIDATION_ERROR,
                                "Profile is already linked to a PDF extraction",
                                Map.of("profileId", String.valueOf(existingProfileId)),
                                null
                        );
                    });
        }

        ProfileWriteRequest profileWriteRequest = profileTextExtractor.extractProfile(new ProfileTextExtractionInput(
                storedExtraction.extraction().text(),
                storedExtraction.document().originalFileName()
        ));

        boolean overwriteExisting = request.overwriteExistingProfile() != null && request.overwriteExistingProfile();
        ProfileAggregate profileAggregate;
        boolean createdProfile;
        if (existingProfileId == null) {
            profileAggregate = profileService.createProfile(profileWriteRequest);
            createdProfile = true;
        } else if (overwriteExisting) {
            profileAggregate = profileService.updateProfile(existingProfileId, profileWriteRequest);
            createdProfile = false;
        } else {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Existing profile ingestion requires overwriteExistingProfile=true",
                    Map.of("profileId", String.valueOf(existingProfileId)),
                    null
            );
        }

        ProfilePdfSource source = profilePdfSourceRepository.save(new ProfilePdfSource(
                UUID.randomUUID(),
                profileAggregate.profile().id(),
                extractionId,
                SOURCE_TYPE,
                clock.instant()
        ));
        return toResult(source, storedExtraction, createdProfile, false);
    }

    private static ProfilePdfIngestionResult toResult(
            ProfilePdfSource source,
            StoredPdfTextExtractionResult storedExtraction,
            boolean createdProfile,
            boolean existingProfileLink
    ) {
        return new ProfilePdfIngestionResult(
                source.profileId(),
                storedExtraction.document().id(),
                source.pdfExtractionId(),
                source.id(),
                storedExtraction.document().originalFileName(),
                storedExtraction.extraction().pageCount(),
                storedExtraction.extraction().characterCount(),
                storedExtraction.extraction().truncated(),
                createdProfile,
                existingProfileLink
        );
    }

    public record IngestProfileFromStoredPdfRequest(
            UUID documentId,
            UUID existingProfileId,
            Boolean overwriteExistingProfile,
            Integer maxCharacters
    ) {
    }

    public record ProfilePdfIngestionResult(
            UUID profileId,
            UUID documentId,
            UUID pdfExtractionId,
            UUID sourceLinkId,
            String originalFileName,
            int pageCount,
            int characterCount,
            boolean truncated,
            boolean createdProfile,
            boolean existingProfileLink
    ) {
    }
}
