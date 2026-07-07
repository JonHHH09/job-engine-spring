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
        ProfileRecordSupport.requireId(id, "id");
        ProfileRecordSupport.requireId(profileId, "profileId");
        ProfileRecordSupport.requireText(language, "language");
        normalizedLanguage = normalizedLanguage == null
                ? ProfileRecordSupport.normalizeRequiredText(language, "normalizedLanguage")
                : ProfileRecordSupport.normalizeRequiredText(normalizedLanguage, "normalizedLanguage");
        ProfileRecordSupport.requireInstant(createdAt, "createdAt");
    }
}
