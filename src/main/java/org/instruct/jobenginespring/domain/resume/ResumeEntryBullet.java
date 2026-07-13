package org.instruct.jobenginespring.domain.resume;

import java.util.Objects;
import java.util.UUID;

public record ResumeEntryBullet(
        UUID id,
        UUID entryId,
        int displayOrder,
        String text
) {
    public ResumeEntryBullet {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(entryId, "entryId must not be null");
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
