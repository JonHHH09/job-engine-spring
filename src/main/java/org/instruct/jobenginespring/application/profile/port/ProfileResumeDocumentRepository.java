package org.instruct.jobenginespring.application.profile.port;

import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Application port for generated resume PDF document links owned by a profile and resume variant. */
public interface ProfileResumeDocumentRepository {

    ProfileResumeDocument save(ProfileResumeDocument resumeDocument);

    Optional<ProfileResumeDocument> findByProfileIdAndResumeType(UUID profileId, String resumeType);

    Optional<ProfileResumeDocument> findByDocumentId(UUID documentId);

    /**
     * Serializes generated-resume mutations for a profile and returns its current links.
     * Callers must invoke this inside the transaction that deletes the profile.
     */
    List<ProfileResumeDocument> lockAndFindAllByProfileId(UUID profileId);

    /** Atomically serializes and replaces one profile/resume-type link, returning the prior link. */
    Replacement replace(ProfileResumeDocument resumeDocument);

    record Replacement(ProfileResumeDocument saved, Optional<ProfileResumeDocument> previous) {
    }
}
