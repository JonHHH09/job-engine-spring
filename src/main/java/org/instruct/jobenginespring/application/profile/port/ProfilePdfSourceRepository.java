package org.instruct.jobenginespring.application.profile.port;

import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;

import java.util.Optional;
import java.util.UUID;

/** Application port for profile-to-PDF-extraction provenance links. */
public interface ProfilePdfSourceRepository {

    record LinkedPdfSource(
            ProfilePdfSource source,
            UUID documentId,
            String originalFileName,
            int pageCount,
            int characterCount,
            boolean truncated
    ) {
    }

    default void acquireIngestionLock(String hashedLockKey) {
        // Non-PostgreSQL adapters may serialize writes through their own storage primitive.
    }

    ProfilePdfSource save(ProfilePdfSource source);

    Optional<ProfilePdfSource> findByProfileId(UUID profileId);

    Optional<ProfilePdfSource> findByPdfExtractionId(UUID pdfExtractionId);

    Optional<ProfilePdfSource> findByDocumentSha256(String sha256);

    Optional<LinkedPdfSource> findLinkedByProfileId(UUID profileId);

    Optional<LinkedPdfSource> findLinkedByPdfExtractionId(UUID pdfExtractionId);

    Optional<LinkedPdfSource> findLinkedByDocumentSha256(String sha256);
}
