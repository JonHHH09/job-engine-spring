package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Experience child record in the profile schema. */
public record Experience(
        UUID id,
        UUID profileId,
        String company,
        String title,
        String location,
        LocalDate startDate,
        LocalDate endDate,
        String description,
        int displayOrder,
        Instant createdAt
) {
    public Experience {
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
    }
}
