package org.instruct.jobenginespring.domain.resume;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Parent tailored resume row: one current Germany pack per profile+job. */
public record Resume(
        UUID id,
        UUID profileId,
        UUID jobId,
        String format,
        Instant profileRevision,
        Instant jobRevision,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String FORMAT_GERMANY = "germany";

    public Resume {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(format, "format must not be null");
        format = format.strip().toLowerCase(Locale.ROOT);
        if (format.isEmpty()) {
            throw new IllegalArgumentException("format must not be blank");
        }
        if (!FORMAT_GERMANY.equals(format)) {
            throw new IllegalArgumentException("unsupported resume format: " + format);
        }
        Objects.requireNonNull(profileRevision, "profileRevision must not be null");
        Objects.requireNonNull(jobRevision, "jobRevision must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
