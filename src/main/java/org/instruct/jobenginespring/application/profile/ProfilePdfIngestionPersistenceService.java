package org.instruct.jobenginespring.application.profile;

import org.apache.commons.codec.digest.DigestUtils;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestionStatus;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.ProfilePdfIngestionResult;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persists a pre-extracted PDF profile in one short transaction after all PDF/provider work completes. */
@Service
public class ProfilePdfIngestionPersistenceService {

    private static final String SOURCE_TYPE = "resume_pdf";

    private final ProfileService profileService;
    private final ProfileIdentityMatcher profileIdentityMatcher;
    private final ProfilePdfSourceRepository profilePdfSourceRepository;
    private final Clock clock;

    @Autowired
    public ProfilePdfIngestionPersistenceService(
            ProfileService profileService,
            ProfileIdentityMatcher profileIdentityMatcher,
            ProfilePdfSourceRepository profilePdfSourceRepository
    ) {
        this(profileService, profileIdentityMatcher, profilePdfSourceRepository, Clock.systemUTC());
    }

    ProfilePdfIngestionPersistenceService(
            ProfileService profileService,
            ProfileIdentityMatcher profileIdentityMatcher,
            ProfilePdfSourceRepository profilePdfSourceRepository,
            Clock clock
    ) {
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.profileIdentityMatcher = Objects.requireNonNull(profileIdentityMatcher, "profileIdentityMatcher must not be null");
        this.profilePdfSourceRepository = Objects.requireNonNull(profilePdfSourceRepository, "profilePdfSourceRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ProfilePdfIngestionResult persist(
            IngestProfileFromStoredPdfRequest request,
            StoredPdfTextExtractionResult storedExtraction,
            ProfileWriteRequest profileWriteRequest
    ) {
        UUID extractionId = Objects.requireNonNull(storedExtraction.extractionId(), "extractionId must not be null");
        acquireLock("document:" + storedExtraction.document().sha256());
        var existingResult = profilePdfSourceRepository.findLinkedByPdfExtractionId(extractionId)
                .map(ProfilePdfIngestionService::reusedResult)
                .or(() -> profilePdfSourceRepository.findLinkedByDocumentSha256(storedExtraction.document().sha256())
                        .map(ProfilePdfIngestionService::reusedResult));
        if (existingResult.isPresent()) {
            return existingResult.orElseThrow();
        }

        Objects.requireNonNull(profileWriteRequest, "profileWriteRequest must not be null");
        profileIdentityMatcher.concurrencyKeys(profileWriteRequest)
                .forEach(profilePdfSourceRepository::acquireIngestionLock);
        UUID existingProfileId = request.existingProfileId();
        if (existingProfileId != null) {
            acquireLock("profile:" + existingProfileId);
            if (profilePdfSourceRepository.findLinkedByProfileId(existingProfileId).isPresent()) {
                return ProfilePdfIngestionService.alreadyLinkedResult(existingProfileId, storedExtraction);
            }
        }

        boolean overwriteExisting = request.overwriteExistingProfile() != null && request.overwriteExistingProfile();
        ProfileAggregate profileAggregate;
        boolean createdProfile;
        IngestionStatus status;
        if (existingProfileId == null) {
            ProfileIdentityMatcher.ProfileIdentityMatch identityMatch = profileIdentityMatcher.findStrongMatch(profileWriteRequest)
                    .orElse(null);
            if (identityMatch != null) {
                return ProfilePdfIngestionService.duplicateCandidateResult(identityMatch, storedExtraction);
            }
            profileAggregate = profileService.createProfile(profileWriteRequest);
            createdProfile = true;
            status = IngestionStatus.CREATED_PROFILE;
        } else if (overwriteExisting) {
            profileAggregate = profileService.updateProfile(
                    existingProfileId, request.expectedRevision(), profileWriteRequest
            );
            createdProfile = false;
            status = IngestionStatus.UPDATED_PROFILE;
        } else {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Existing profile ingestion requires overwriteExistingProfile=true",
                    Map.of("profileId", String.valueOf(existingProfileId)),
                    null
            );
        }

        var inserted = profilePdfSourceRepository.insertOrFind(new ProfilePdfSource(
                UUID.randomUUID(),
                profileAggregate.profile().id(),
                extractionId,
                SOURCE_TYPE,
                clock.instant()
        ));
        if (inserted.inserted()) {
            return ProfilePdfIngestionService.toResult(
                    inserted.source(), storedExtraction, status, createdProfile, false
            );
        }
        if (createdProfile) {
            profileService.deleteProfile(profileAggregate.profile().id());
        }
        if (!inserted.source().pdfExtractionId().equals(extractionId)
                || (!createdProfile && !inserted.source().profileId().equals(profileAggregate.profile().id()))) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Profile is already linked to a PDF extraction",
                    Map.of("profileId", String.valueOf(profileAggregate.profile().id())),
                    null
            );
        }
        return ProfilePdfIngestionService.toResult(
                inserted.source(), storedExtraction, IngestionStatus.REUSED_EXISTING_SOURCE, false, true
        );
    }

    private void acquireLock(String rawLockKey) {
        profilePdfSourceRepository.acquireIngestionLock(DigestUtils.sha256Hex(rawLockKey));
    }
}
