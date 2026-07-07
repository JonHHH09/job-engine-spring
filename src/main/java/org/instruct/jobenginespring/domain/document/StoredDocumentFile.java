package org.instruct.jobenginespring.domain.document;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record StoredDocumentFile(
        UUID id,
        String originalFileName,
        String mediaType,
        long byteSize,
        String sha256,
        byte[] content,
        Instant createdAt,
        Instant updatedAt
) {
    public StoredDocumentFile {
        Objects.requireNonNull(content, "content must not be null");
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    public StoredDocumentMetadata metadata() {
        return new StoredDocumentMetadata(id, originalFileName, mediaType, byteSize, sha256, createdAt, updatedAt);
    }
}
