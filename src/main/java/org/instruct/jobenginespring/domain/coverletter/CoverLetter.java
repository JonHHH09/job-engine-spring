package org.instruct.jobenginespring.domain.coverletter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Generic cover-letter parent linked to the exact profile, job, and resume source. */
public record CoverLetter(
        UUID id,
        UUID profileId,
        UUID jobId,
        UUID resumeId,
        Instant profileRevision,
        Instant jobRevision,
        Instant resumeRevision,
        Instant createdAt,
        Instant updatedAt
) {
    public CoverLetter {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(resumeId, "resumeId must not be null");
        Objects.requireNonNull(profileRevision, "profileRevision must not be null");
        Objects.requireNonNull(jobRevision, "jobRevision must not be null");
        Objects.requireNonNull(resumeRevision, "resumeRevision must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
