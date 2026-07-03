package org.instruct.jobenginespring.domain.document;

import java.time.Instant;
import java.util.UUID;

public record PdfExtractionRecord(
        UUID id,
        UUID fileId,
        String extractor,
        int characterCount,
        int pageCount,
        boolean truncated,
        String extractedText,
        Instant createdAt
) {
}
