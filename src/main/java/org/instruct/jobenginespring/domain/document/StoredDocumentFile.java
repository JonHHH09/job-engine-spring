package org.instruct.jobenginespring.domain.document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
        if (byteSize != content.length) {
            throw new IllegalArgumentException("byteSize must equal content length");
        }
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    /** Opens an immutable read view without copying the complete stored content. */
    public InputStream openContentStream() {
        return new ByteArrayInputStream(content);
    }

    /** Returns the length of the immutable stored content. */
    public int contentLength() {
        return content.length;
    }

    public StoredDocumentMetadata metadata() {
        return new StoredDocumentMetadata(id, originalFileName, mediaType, byteSize, sha256, createdAt, updatedAt);
    }
}
