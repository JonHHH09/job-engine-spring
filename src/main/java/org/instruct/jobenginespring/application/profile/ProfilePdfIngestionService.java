package org.instruct.jobenginespring.application.profile;

import lombok.NonNull;
import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.ExtractStoredPdfTextRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository.LinkedPdfSource;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor.ProfileTextExtractionInput;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProfilePdfIngestionService {

    @NonNull
    private final DocumentStorageService documentStorageService;
    @NonNull
    private final ProfileTextExtractor profileTextExtractor;
    @NonNull
    private final ProfilePdfSourceRepository profilePdfSourceRepository;
    @NonNull
    private final ProfilePdfIngestionPersistenceService persistenceService;

    @Autowired
    public ProfilePdfIngestionService(
            DocumentStorageService documentStorageService,
            ProfileTextExtractor profileTextExtractor,
            ProfilePdfSourceRepository profilePdfSourceRepository,
            ProfilePdfIngestionPersistenceService persistenceService
    ) {
        this.documentStorageService = Objects.requireNonNull(documentStorageService, "documentStorageService must not be null");
        this.profileTextExtractor = Objects.requireNonNull(profileTextExtractor, "profileTextExtractor must not be null");
        this.profilePdfSourceRepository = Objects.requireNonNull(profilePdfSourceRepository, "profilePdfSourceRepository must not be null");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService must not be null");
    }

    ProfilePdfIngestionService(
            DocumentStorageService documentStorageService,
            ProfileTextExtractor profileTextExtractor,
            ProfileService profileService,
            ProfileIdentityMatcher profileIdentityMatcher,
            ProfilePdfSourceRepository profilePdfSourceRepository
    ) {
        this(documentStorageService, profileTextExtractor, profileService, profileIdentityMatcher,
                profilePdfSourceRepository, Clock.systemUTC());
    }

    ProfilePdfIngestionService(
            DocumentStorageService documentStorageService,
            ProfileTextExtractor profileTextExtractor,
            ProfileService profileService,
            ProfileIdentityMatcher profileIdentityMatcher,
            ProfilePdfSourceRepository profilePdfSourceRepository,
            Clock clock
    ) {
        this(
                documentStorageService,
                profileTextExtractor,
                profilePdfSourceRepository,
                new ProfilePdfIngestionPersistenceService(
                        profileService,
                        profileIdentityMatcher,
                        profilePdfSourceRepository,
                        Objects.requireNonNull(clock, "clock must not be null")
                )
        );
    }

    public ProfilePdfIngestionResult ingestProfileFromStoredPdf(IngestProfileFromStoredPdfRequest request) {
        IngestProfileFromStoredPdfRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        UUID documentId = Objects.requireNonNull(safeRequest.documentId(), "documentId must not be null");
        StoredPdfTextExtractionResult storedExtraction = documentStorageService.extractStoredPdfText(
                new ExtractStoredPdfTextRequest(documentId, safeRequest.maxCharacters(), false, true)
        );
        UUID extractionId = Objects.requireNonNull(storedExtraction.extractionId(), "extractionId must not be null");
        String documentSha256 = storedExtraction.document().sha256();

        var existingResult = profilePdfSourceRepository.findLinkedByPdfExtractionId(extractionId)
                .map(ProfilePdfIngestionService::reusedResult)
                .or(() -> profilePdfSourceRepository.findLinkedByDocumentSha256(documentSha256)
                        .map(ProfilePdfIngestionService::reusedResult));
        if (existingResult.isPresent()) {
            return existingResult.orElseThrow();
        }
        if (safeRequest.existingProfileId() != null
                && profilePdfSourceRepository.findLinkedByProfileId(safeRequest.existingProfileId()).isPresent()) {
            return alreadyLinkedResult(safeRequest.existingProfileId(), storedExtraction);
        }

        ProfileWriteRequest profileWriteRequest = profileTextExtractor.extractProfile(new ProfileTextExtractionInput(
                storedExtraction.extraction().text(),
                storedExtraction.document().originalFileName()
        ));
        return persistenceService.persist(safeRequest, storedExtraction, profileWriteRequest);
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

    static ProfilePdfIngestionResult duplicateCandidateResult(
            ProfileIdentityMatcher.ProfileIdentityMatch match,
            StoredPdfTextExtractionResult storedExtraction
    ) {
        return new ProfilePdfIngestionResult(
                match.ambiguous() ? IngestionStatus.AMBIGUOUS_PROFILE_CANDIDATES : IngestionStatus.DUPLICATE_PROFILE_CANDIDATE,
                null,
                storedExtraction.document().id(),
                storedExtraction.extractionId(),
                null,
                storedExtraction.document().originalFileName(),
                storedExtraction.extraction().pageCount(),
                storedExtraction.extraction().characterCount(),
                storedExtraction.extraction().truncated(),
                false,
                false,
                match.profileId(),
                match.matchedOn(),
                "rerun with existingProfileId and overwriteExistingProfile=true to replace"
        );
    }

    static ProfilePdfIngestionResult alreadyLinkedResult(
            UUID existingProfileId,
            StoredPdfTextExtractionResult storedExtraction
    ) {
        return new ProfilePdfIngestionResult(
                IngestionStatus.DUPLICATE_PROFILE_CANDIDATE,
                null,
                storedExtraction.document().id(),
                storedExtraction.extractionId(),
                null,
                storedExtraction.document().originalFileName(),
                storedExtraction.extraction().pageCount(),
                storedExtraction.extraction().characterCount(),
                storedExtraction.extraction().truncated(),
                false,
                false,
                existingProfileId,
                List.of("profileId"),
                "profile already has a linked PDF source; no mutation was performed"
        );
    }

    static ProfilePdfIngestionResult reusedResult(LinkedPdfSource linked) {
        ProfilePdfSource source = linked.source();
        return new ProfilePdfIngestionResult(
                IngestionStatus.REUSED_EXISTING_SOURCE,
                source.profileId(),
                linked.documentId(),
                source.pdfExtractionId(),
                source.id(),
                linked.originalFileName(),
                linked.pageCount(),
                linked.characterCount(),
                linked.truncated(),
                false,
                true,
                null,
                List.of(),
                null
        );
    }

    static ProfilePdfIngestionResult toResult(
            ProfilePdfSource source,
            StoredPdfTextExtractionResult storedExtraction,
            IngestionStatus status,
            boolean createdProfile,
            boolean existingProfileLink
    ) {
        return new ProfilePdfIngestionResult(
                status,
                source.profileId(),
                storedExtraction.document().id(),
                source.pdfExtractionId(),
                source.id(),
                storedExtraction.document().originalFileName(),
                storedExtraction.extraction().pageCount(),
                storedExtraction.extraction().characterCount(),
                storedExtraction.extraction().truncated(),
                createdProfile,
                existingProfileLink,
                null,
                List.of(),
                null
        );
    }

    public record IngestProfileFromStoredPdfRequest(
            UUID documentId,
            UUID existingProfileId,
            Boolean overwriteExistingProfile,
            Integer maxCharacters,
            Long expectedRevision
    ) {
        public IngestProfileFromStoredPdfRequest(
                UUID documentId,
                UUID existingProfileId,
                Boolean overwriteExistingProfile,
                Integer maxCharacters
        ) {
            this(documentId, existingProfileId, overwriteExistingProfile, maxCharacters, null);
        }
    }

    public record ProfilePdfIngestionResult(
            IngestionStatus status,
            UUID profileId,
            UUID documentId,
            UUID pdfExtractionId,
            UUID sourceLinkId,
            String originalFileName,
            int pageCount,
            int characterCount,
            boolean truncated,
            boolean createdProfile,
            boolean existingProfileLink,
            UUID candidateProfileId,
            List<String> matchedOn,
            String recommendedAction
    ) {
        public ProfilePdfIngestionResult {
            Objects.requireNonNull(status, "status must not be null");
            matchedOn = matchedOn == null ? List.of() : List.copyOf(matchedOn);
        }
    }

    public enum IngestionStatus {
        CREATED_PROFILE,
        UPDATED_PROFILE,
        REUSED_EXISTING_SOURCE,
        DUPLICATE_PROFILE_CANDIDATE,
        AMBIGUOUS_PROFILE_CANDIDATES
    }
}
