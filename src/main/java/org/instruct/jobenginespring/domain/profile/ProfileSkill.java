package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.UUID;

/** Normalized skill row for searchable profile matching. */
public record ProfileSkill(
        UUID id,
        UUID profileId,
        String skill,
        String normalizedSkill,
        String category,
        int displayOrder,
        Instant createdAt
) {
    public ProfileSkill {
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        skill = ProfileRecordSupport.requireText(skill, "skill");
        normalizedSkill = normalizedSkill == null
                ? ProfileRecordSupport.normalizeRequiredText(skill, "normalizedSkill")
                : ProfileRecordSupport.normalizeRequiredText(normalizedSkill, "normalizedSkill");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
    }
}
