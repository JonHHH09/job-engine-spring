package org.instruct.jobenginespring.domain.resume;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ResumeSection(
        UUID id,
        UUID variantId,
        String sectionType,
        String title,
        int displayOrder
) {
    public static final String PERSONAL = "personal";
    public static final String EXPERIENCE = "experience";
    public static final String EDUCATION = "education";
    public static final String SKILLS = "skills";
    public static final String LANGUAGES = "languages";
    public static final String ADDITIONAL = "additional";

    public ResumeSection {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(variantId, "variantId must not be null");
        Objects.requireNonNull(sectionType, "sectionType must not be null");
        sectionType = sectionType.strip().toLowerCase(Locale.ROOT);
        if (sectionType.isEmpty()) {
            throw new IllegalArgumentException("sectionType must not be blank");
        }
        Objects.requireNonNull(title, "title must not be null");
        title = title.strip();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must be non-negative");
        }
    }
}
