package org.instruct.jobenginespring.application.profile.port;

import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;

import java.util.Optional;
import java.util.UUID;

/** Application port for generated resume PDF document links owned by a profile and resume variant. */
public interface ProfileResumeDocumentRepository {

    ProfileResumeDocument save(ProfileResumeDocument resumeDocument);

    Optional<ProfileResumeDocument> findByProfileIdAndResumeType(UUID profileId, String resumeType);

    Optional<ProfileResumeDocument> findByDocumentId(UUID documentId);
}
