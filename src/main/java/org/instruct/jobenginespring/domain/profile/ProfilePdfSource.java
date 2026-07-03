package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.UUID;

/** Links a normalized profile aggregate to the PDF extraction it was populated from. */
public record ProfilePdfSource(
        UUID id,
        UUID profileId,
        UUID pdfExtractionId,
        String sourceType,
        Instant createdAt
) {
}
