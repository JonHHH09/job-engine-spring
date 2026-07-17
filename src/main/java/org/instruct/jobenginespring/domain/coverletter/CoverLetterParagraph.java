package org.instruct.jobenginespring.domain.coverletter;

import java.util.Objects;
import java.util.UUID;

/** One ordered, persisted paragraph of a cover-letter variant. */
public record CoverLetterParagraph(
        UUID id,
        UUID variantId,
        int displayOrder,
        String text
) {
    public CoverLetterParagraph {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(variantId, "variantId must not be null");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must be non-negative");
        }
        Objects.requireNonNull(text, "text must not be null");
        text = text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
