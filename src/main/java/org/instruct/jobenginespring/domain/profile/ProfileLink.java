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
        ProfileRecordSupport.requireId(id, "id");
        ProfileRecordSupport.requireId(profileId, "profileId");
        linkType = ProfileRecordSupport.normalizeRequiredText(linkType, "linkType");
        ProfileRecordSupport.requireText(url, "url");
        ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
    }
}
