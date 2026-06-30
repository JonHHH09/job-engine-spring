package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Education child record in the profile schema. */
public record Education(
        UUID id,
        UUID profileId,
        String institution,
        String degree,
        String field,
        String location,
        LocalDate startDate,
        LocalDate endDate,
        String relevantFocus,
        Instant createdAt
) {
    public Education {
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
    }
}
