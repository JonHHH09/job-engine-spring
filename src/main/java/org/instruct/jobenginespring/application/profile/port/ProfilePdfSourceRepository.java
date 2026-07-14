package org.instruct.jobenginespring.application.profile.port;

import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;

import java.util.Optional;
import java.util.UUID;

/** Application port for profile-to-PDF-extraction provenance links. */
public interface ProfilePdfSourceRepository {

    ProfilePdfSource save(ProfilePdfSource source);

    /** Atomically inserts a source link or returns the source that won a concurrent uniqueness race. */
    default InsertResult insertOrFind(ProfilePdfSource source) {
        return new InsertResult(save(source), true);
    }

    Optional<ProfilePdfSource> findByProfileId(UUID profileId);

    Optional<ProfilePdfSource> findByPdfExtractionId(UUID pdfExtractionId);

    Optional<ProfilePdfSource> findByDocumentSha256(String sha256);

    record InsertResult(ProfilePdfSource source, boolean inserted) {
    }
}
