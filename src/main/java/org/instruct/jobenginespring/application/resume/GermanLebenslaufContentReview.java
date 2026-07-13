package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic pre-PDF content review for German tailored resumes.
 * Fails closed when structured content is incomplete or layout-hostile.
 */
public final class GermanLebenslaufContentReview {

    private GermanLebenslaufContentReview() {
    }

    public static void review(StructuredResumeContent content) {
        StructuredResumeContent safe = Objects.requireNonNull(content, "content must not be null");
        List<String> defects = new ArrayList<>();

        if (safe.experiences().isEmpty() && safe.education().isEmpty()) {
            defects.add("at least one experience or education entry is required");
        }
        boolean hasEmail = safe.personalFields().stream()
                .anyMatch(field -> field.value().contains("@") || field.label().toLowerCase(Locale.ROOT).contains("mail"));
        if (!hasEmail) {
            defects.add("contact email is required");
        }
        for (StructuredResumeContent.ExperienceEntry experience : safe.experiences()) {
            if (experience.title().isBlank() || experience.company().isBlank()) {
                defects.add("experience title and company must not be blank");
            }
        }
        for (StructuredResumeContent.EducationEntry education : safe.education()) {
            if (education.degree().isBlank() || education.institution().isBlank()) {
                defects.add("education degree and institution must not be blank");
            }
        }

        String rendered = GermanLebenslaufBodyRenderer.render(safe);
        if (rendered.length() < 40) {
            defects.add("rendered body is too short to be a usable resume");
        }

        if (!defects.isEmpty()) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "German tailored resume content review failed",
                    Map.of("defects", String.join("; ", defects)),
                    null
            );
        }
    }
}
