package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.UUID;

/** Normalized contact channel for a profile, such as phone or location. */
public record ProfileContact(
        UUID id,
        UUID profileId,
        String contactType,
        String contactValue,
        String label,
        Instant createdAt,
        Instant updatedAt
) {
    public ProfileContact {
        ProfileRecordSupport.requireId(id, "id");
        ProfileRecordSupport.requireId(profileId, "profileId");
        contactType = ProfileRecordSupport.normalizeRequiredText(contactType, "contactType");
        ProfileRecordSupport.requireText(contactValue, "contactValue");
        ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
    }
}
