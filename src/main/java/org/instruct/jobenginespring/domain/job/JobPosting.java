package org.instruct.jobenginespring.domain.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record JobPosting(
        UUID id,
        String sourceMethod,
        String sourceLabel,
        String title,
        String company,
        String location,
        String description,
        String experienceRequirement,
        String employmentType,
        String seniority,
        Instant postedAt,
        String canonicalFingerprint,
        Instant createdAt,
        Instant updatedAt
) {
    public JobPosting {
        Objects.requireNonNull(id, "id must not be null");
        requireText(sourceMethod, "sourceMethod");
        requireText(title, "title");
        requireText(description, "description");
        requireText(canonicalFingerprint, "canonicalFingerprint");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
