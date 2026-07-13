package org.instruct.jobenginespring.domain.resume;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ResumeEntry(
        UUID id,
        UUID sectionId,
        String entryType,
        int displayOrder,
        String title,
        String organization,
        String location,
        LocalDate startDate,
        LocalDate endDate,
        String metadata
) {
    public static final String PERSONAL_FIELD = "personal_field";
    public static final String EXPERIENCE = "experience";
    public static final String EDUCATION = "education";
    public static final String SKILL_GROUP = "skill_group";
    public static final String LANGUAGE = "language";
    public static final String PROJECT = "project";

    public ResumeEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sectionId, "sectionId must not be null");
        Objects.requireNonNull(entryType, "entryType must not be null");
        entryType = entryType.strip().toLowerCase(Locale.ROOT);
        if (entryType.isEmpty()) {
            throw new IllegalArgumentException("entryType must not be blank");
        }
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must be non-negative");
        }
        title = blankToNull(title);
        organization = blankToNull(organization);
        location = blankToNull(location);
        metadata = blankToNull(metadata);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
