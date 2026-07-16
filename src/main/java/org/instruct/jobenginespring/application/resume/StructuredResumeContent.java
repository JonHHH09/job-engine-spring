package org.instruct.jobenginespring.application.resume;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Language-neutral structured Lebenslauf content used before persistence and PDF rendering.
 */
public record StructuredResumeContent(
        String fullName,
        String language,
        String summary,
        List<PersonalField> personalFields,
        List<ExperienceEntry> experiences,
        List<EducationEntry> education,
        List<SkillGroup> skillGroups,
        List<LanguageEntry> languages,
        List<AdditionalEntry> additional
) {
    public StructuredResumeContent {
        Objects.requireNonNull(fullName, "fullName must not be null");
        fullName = fullName.strip();
        if (fullName.isEmpty()) {
            throw new IllegalArgumentException("fullName must not be blank");
        }
        Objects.requireNonNull(language, "language must not be null");
        language = language.strip().toLowerCase();
        summary = blankToNull(summary);
        personalFields = copy(personalFields);
        experiences = copy(experiences);
        education = copy(education);
        skillGroups = copy(skillGroups);
        languages = copy(languages);
        additional = copy(additional);
    }

    public StructuredResumeContent(
            String fullName,
            String language,
            List<PersonalField> personalFields,
            List<ExperienceEntry> experiences,
            List<EducationEntry> education,
            List<SkillGroup> skillGroups,
            List<LanguageEntry> languages,
            List<AdditionalEntry> additional
    ) {
        this(fullName, language, null, personalFields, experiences, education, skillGroups, languages, additional);
    }

    private static <T> List<T> copy(List<T> values) {
        return List.copyOf(values == null ? List.of() : values);
    }

    public record PersonalField(String label, String value) {
        public PersonalField {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(value, "value must not be null");
            label = label.strip();
            value = value.strip();
            if (label.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("personal field label/value must not be blank");
            }
        }
    }

    public record ExperienceEntry(
            String title,
            String company,
            String location,
            LocalDate startDate,
            LocalDate endDate,
            List<String> bullets
    ) {
        public ExperienceEntry {
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(company, "company must not be null");
            title = title.strip();
            company = company.strip();
            location = blankToNull(location);
            bullets = normalizeBullets(bullets);
        }
    }

    public record EducationEntry(
            String degree,
            String institution,
            String location,
            LocalDate startDate,
            LocalDate endDate,
            List<String> bullets
    ) {
        public EducationEntry {
            Objects.requireNonNull(degree, "degree must not be null");
            Objects.requireNonNull(institution, "institution must not be null");
            degree = degree.strip();
            institution = institution.strip();
            location = blankToNull(location);
            bullets = normalizeBullets(bullets);
        }
    }

    public record SkillGroup(String category, List<String> skills) {
        public SkillGroup {
            Objects.requireNonNull(category, "category must not be null");
            category = category.strip();
            if (category.isEmpty()) {
                throw new IllegalArgumentException("category must not be blank");
            }
            skills = normalizeBullets(skills);
            if (skills.isEmpty()) {
                throw new IllegalArgumentException("skills must not be empty");
            }
        }
    }

    public record LanguageEntry(String language, String proficiency) {
        public LanguageEntry {
            Objects.requireNonNull(language, "language must not be null");
            language = language.strip();
            proficiency = blankToNull(proficiency);
        }
    }

    public record AdditionalEntry(String title, String organization, List<String> bullets) {
        public AdditionalEntry {
            Objects.requireNonNull(title, "title must not be null");
            title = title.strip();
            organization = blankToNull(organization);
            bullets = normalizeBullets(bullets);
        }
    }

    private static List<String> normalizeBullets(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String bullet : bullets) {
            if (bullet == null) {
                continue;
            }
            String stripped = bullet.strip();
            if (!stripped.isEmpty()) {
                normalized.add(stripped);
            }
        }
        return List.copyOf(normalized);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
