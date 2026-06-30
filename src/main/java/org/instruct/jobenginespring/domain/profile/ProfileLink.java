package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.UUID;

/** Normalized external profile link, such as LinkedIn, GitHub, or portfolio. */
public record ProfileLink(
        UUID id,
        UUID profileId,
        String linkType,
        String url,
        String label,
        Instant createdAt,
        Instant updatedAt
) {
    public ProfileLink {
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        linkType = ProfileRecordSupport.normalizeRequiredText(linkType, "linkType");
        url = ProfileRecordSupport.requireText(url, "url");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        updatedAt = ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
    }
}
