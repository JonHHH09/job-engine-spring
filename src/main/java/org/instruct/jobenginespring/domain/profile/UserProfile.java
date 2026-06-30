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
        List<Double> embedding
) {
    public UserProfile {
        id = ProfileRecordSupport.requireId(id, "id");
        fullName = ProfileRecordSupport.requireText(fullName, "fullName");
        email = ProfileRecordSupport.requireText(email, "email");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        updatedAt = ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
        embedding = ProfileRecordSupport.immutableCopy(embedding);
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
        this(id, fullName, email, summary, rawResumeText, createdAt, updatedAt, null);
    }
}
