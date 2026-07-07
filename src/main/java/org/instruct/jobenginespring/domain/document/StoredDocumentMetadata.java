package org.instruct.jobenginespring.domain.document;

import java.time.Instant;
import java.util.UUID;

public record StoredDocumentMetadata(
        UUID id,
        String originalFileName,
        String mediaType,
        long byteSize,
        String sha256,
        Instant createdAt,
        Instant updatedAt
) {
}
