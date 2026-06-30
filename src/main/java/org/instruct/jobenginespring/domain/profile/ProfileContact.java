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
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        contactType = ProfileRecordSupport.normalizeRequiredText(contactType, "contactType");
        contactValue = ProfileRecordSupport.requireText(contactValue, "contactValue");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        updatedAt = ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
    }
}
