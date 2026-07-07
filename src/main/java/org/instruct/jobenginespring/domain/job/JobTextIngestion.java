package org.instruct.jobenginespring.domain.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record JobTextIngestion(
        UUID id,
        UUID jobId,
        String sourceLabel,
        String inputTextHash,
        Instant createdAt
) {
    public JobTextIngestion {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        if (inputTextHash == null || inputTextHash.isBlank()) {
            throw new IllegalArgumentException("inputTextHash must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
