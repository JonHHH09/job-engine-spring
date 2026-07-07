package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.UUID;

/** Links a profile to its current generated master resume PDF document. */
public record ProfileResumeDocument(
        UUID id,
        UUID profileId,
        UUID documentId,
        String filePath,
        String resumeType,
        Instant createdAt,
        Instant updatedAt
) {
    public ProfileResumeDocument {
        ProfileRecordSupport.requireId(id, "id");
        ProfileRecordSupport.requireId(profileId, "profileId");
        ProfileRecordSupport.requireId(documentId, "documentId");
        ProfileRecordSupport.requireText(filePath, "filePath");
        resumeType = ProfileRecordSupport.normalizeRequiredText(resumeType, "resumeType");
        ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
    }
}
