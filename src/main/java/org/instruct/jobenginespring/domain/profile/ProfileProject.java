package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Project child record in the profile schema. */
public record ProfileProject(
        UUID id,
        UUID profileId,
        String name,
        String url,
        String description,
        List<ProjectTechnology> technologies,
        int displayOrder,
        Instant createdAt
) {
    public ProfileProject {
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        technologies = ProfileRecordSupport.immutableCopy(technologies);
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
    }

    public ProfileProject(
            UUID id,
            UUID profileId,
            String name,
            String url,
            String description,
            int displayOrder,
            Instant createdAt
    ) {
        this(id, profileId, name, url, description, null, displayOrder, createdAt);
    }
}
