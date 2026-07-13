package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Optional Germany-application personal details linked 1:1 to a profile. Photo is fully optional. */
public record ProfilePersonalDetails(
        UUID profileId,
        LocalDate dateOfBirth,
        String nationality,
        UUID photoDocumentId,
        Instant createdAt,
        Instant updatedAt
) {
    public ProfilePersonalDetails {
        ProfileRecordSupport.requireId(profileId, "profileId");
        if (nationality != null) {
            nationality = nationality.strip();
            if (nationality.isEmpty()) {
                nationality = null;
            }
        }
        ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public boolean hasPhoto() {
        return photoDocumentId != null;
    }
}
