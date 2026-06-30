package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.UUID;

/** Normalized spoken or written language for a profile. */
public record ProfileLanguage(
        UUID id,
        UUID profileId,
        String language,
        String normalizedLanguage,
        String proficiency,
        int displayOrder,
        Instant createdAt
) {
    public ProfileLanguage {
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        language = ProfileRecordSupport.requireText(language, "language");
        normalizedLanguage = normalizedLanguage == null
                ? ProfileRecordSupport.normalizeRequiredText(language, "normalizedLanguage")
                : ProfileRecordSupport.normalizeRequiredText(normalizedLanguage, "normalizedLanguage");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
    }
}
