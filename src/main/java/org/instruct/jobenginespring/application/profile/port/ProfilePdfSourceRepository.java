package org.instruct.jobenginespring.application.profile.port;

import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;

import java.util.Optional;
import java.util.UUID;

/** Application port for profile-to-PDF-extraction provenance links. */
public interface ProfilePdfSourceRepository {

    default void acquireIngestionLock(String hashedLockKey) {
        // Non-PostgreSQL adapters may serialize writes through their own storage primitive.
    }

    ProfilePdfSource save(ProfilePdfSource source);

    Optional<ProfilePdfSource> findByProfileId(UUID profileId);

    Optional<ProfilePdfSource> findByPdfExtractionId(UUID pdfExtractionId);

    Optional<ProfilePdfSource> findByDocumentSha256(String sha256);
}
