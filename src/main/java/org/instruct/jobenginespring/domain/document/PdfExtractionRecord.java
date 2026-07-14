package org.instruct.jobenginespring.domain.document;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PdfExtractionRecord(
        UUID id,
        UUID fileId,
        String extractor,
        int characterCount,
        int pageCount,
        boolean truncated,
        String extractedText,
        List<PageProjection> pages,
        Instant createdAt
) {
    public PdfExtractionRecord {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages must not be null"));
    }

    public PdfExtractionRecord(
            UUID id,
            UUID fileId,
            String extractor,
            int characterCount,
            int pageCount,
            boolean truncated,
            String extractedText,
            Instant createdAt
    ) {
        this(id, fileId, extractor, characterCount, pageCount, truncated, extractedText, List.of(), createdAt);
    }

    public record PageProjection(int pageNumber, String text) {
    }
}
