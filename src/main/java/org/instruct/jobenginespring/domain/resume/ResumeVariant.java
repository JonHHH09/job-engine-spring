package org.instruct.jobenginespring.domain.resume;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Language-specific resume variant with generated PDF document link. */
public record ResumeVariant(
        UUID id,
        UUID resumeId,
        String language,
        UUID documentId,
        String filePath,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_DE = "de";

    public ResumeVariant {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(resumeId, "resumeId must not be null");
        Objects.requireNonNull(language, "language must not be null");
        language = language.strip().toLowerCase(Locale.ROOT);
        if (!LANGUAGE_EN.equals(language) && !LANGUAGE_DE.equals(language)) {
            throw new IllegalArgumentException("unsupported language: " + language);
        }
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        if (filePath.isBlank()) {
            throw new IllegalArgumentException("filePath must not be blank");
        }
        filePath = filePath.strip();
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
