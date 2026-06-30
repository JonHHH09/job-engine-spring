package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.UUID;

/** Normalized technology attached to a profile project. */
public record ProjectTechnology(
        UUID id,
        UUID projectId,
        String technology,
        String normalizedTechnology,
        int displayOrder,
        Instant createdAt
) {
    public ProjectTechnology {
        id = ProfileRecordSupport.requireId(id, "id");
        projectId = ProfileRecordSupport.requireId(projectId, "projectId");
        technology = ProfileRecordSupport.requireText(technology, "technology");
        normalizedTechnology = normalizedTechnology == null
                ? ProfileRecordSupport.normalizeRequiredText(technology, "normalizedTechnology")
                : ProfileRecordSupport.normalizeRequiredText(normalizedTechnology, "normalizedTechnology");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
    }
}
