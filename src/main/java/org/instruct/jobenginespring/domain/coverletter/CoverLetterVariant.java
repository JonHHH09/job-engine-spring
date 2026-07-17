package org.instruct.jobenginespring.domain.coverletter;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Format/language-specific cover-letter content and generated document link. */
public record CoverLetterVariant(
        UUID id,
        UUID coverLetterId,
        String format,
        String language,
        UUID documentId,
        String filePath,
        String subject,
        String salutation,
        String closing,
        String signature,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String FORMAT_GERMANY = "germany";
    public static final String LANGUAGE_DE = "de";

    public CoverLetterVariant {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(coverLetterId, "coverLetterId must not be null");
        Objects.requireNonNull(format, "format must not be null");
        format = format.strip().toLowerCase(Locale.ROOT);
        if (!FORMAT_GERMANY.equals(format)) {
            throw new IllegalArgumentException("unsupported cover-letter format: " + format);
        }
        Objects.requireNonNull(language, "language must not be null");
        language = language.strip().toLowerCase(Locale.ROOT);
        if (!LANGUAGE_DE.equals(language)) {
            throw new IllegalArgumentException("unsupported cover-letter language: " + language);
        }
        Objects.requireNonNull(documentId, "documentId must not be null");
        filePath = requiredText(filePath, "filePath");
        subject = requiredText(subject, "subject");
        salutation = requiredText(salutation, "salutation");
        closing = requiredText(closing, "closing");
        signature = requiredText(signature, "signature");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static String requiredText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stripped;
    }
}
