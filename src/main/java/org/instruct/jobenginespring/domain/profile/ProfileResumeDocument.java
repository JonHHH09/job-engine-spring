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
        id = ProfileRecordSupport.requireId(id, "id");
        profileId = ProfileRecordSupport.requireId(profileId, "profileId");
        documentId = ProfileRecordSupport.requireId(documentId, "documentId");
        filePath = ProfileRecordSupport.requireText(filePath, "filePath");
        resumeType = ProfileRecordSupport.normalizeRequiredText(resumeType, "resumeType");
        createdAt = ProfileRecordSupport.requireInstant(createdAt, "createdAt");
        updatedAt = ProfileRecordSupport.requireInstant(updatedAt, "updatedAt");
    }
}
