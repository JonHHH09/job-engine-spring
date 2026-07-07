package org.instruct.jobenginespring.domain.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record JobLinkIngestion(
        UUID id,
        UUID jobId,
        String url,
        String normalizedUrl,
        Instant fetchedAt,
        Integer httpStatus,
        String sourceTitle,
        Instant createdAt
) {
    public JobLinkIngestion {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        requireText(url, "url");
        requireText(normalizedUrl, "normalizedUrl");
        Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
