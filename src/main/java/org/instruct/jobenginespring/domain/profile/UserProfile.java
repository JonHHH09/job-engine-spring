package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Core profile identity and summary. Normalized child data lives in the aggregate. */
public record UserProfile(
        UUID id,
        String fullName,
        String email,
        String summary,
        String rawResumeText,
        Instant createdAt,
        Instant updatedAt,
        List<Double> embedding,
        long revision
) {
    public UserProfile {
        ProfileRecordSupport.requireId(id, "id");
        ProfileRecordSupport.requireText(fullName, "fullName");
        ProfileRecordSupport.requireText(email, "email");
        ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        embedding = ProfileRecordSupport.immutableCopy(embedding);
    }

    public UserProfile(
            UUID id,
            String fullName,
            String email,
            String summary,
            String rawResumeText,
            Instant createdAt,
            Instant updatedAt,
            List<Double> embedding
    ) {
        this(id, fullName, email, summary, rawResumeText, createdAt, updatedAt, embedding, 0);
    }

    public UserProfile(
            UUID id,
            String fullName,
            String email,
            String summary,
            String rawResumeText,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, fullName, email, summary, rawResumeText, createdAt, updatedAt, null, 0);
    }
}
