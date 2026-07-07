package org.instruct.jobenginespring.domain.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record JobSkill(
        UUID id,
        UUID jobId,
        String skill,
        String normalizedSkill,
        boolean required,
        int displayOrder,
        Instant createdAt
) {
    public JobSkill {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        requireText(skill, "skill");
        requireText(normalizedSkill, "normalizedSkill");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must be greater than or equal to 0");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
