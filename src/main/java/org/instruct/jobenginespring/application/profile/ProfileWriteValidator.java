package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ContactWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.EducationWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ExperienceWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LanguageWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectTechnologyWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.SkillWriteRequest;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ProfileWriteValidator {

    private static final Pattern BASIC_EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private ProfileWriteValidator() {
    }

    static void validate(ProfileWriteRequest request) {
        if (request == null) {
            throw invalid("request", "must not be null");
        }
        requireText(request.fullName(), "fullName");
        requireText(request.email(), "email");
        if (!BASIC_EMAIL_PATTERN.matcher(request.email().trim()).matches()) {
            throw invalid("email", "must be a valid email address");
        }

        validateContacts(request.contacts());
        validateLinks(request.links());
        validateSkills(request.skills());
        validateLanguages(request.languages());
        validateEducation(request.education());
        validateExperiences(request.experiences());
        validateProjects(request.projects());
    }

    private static void validateContacts(List<ContactWriteRequest> contacts) {
        Set<String> seenContactKeys = new HashSet<>();
        for (int index = 0; index < nullSafe(contacts).size(); index++) {
            ContactWriteRequest contact = nullSafe(contacts).get(index);
            requireItem(contact, "contacts", index);
            String type = normalizeRequired(contact.contactType(), field("contacts", index, "contactType"));
            String value = requireText(contact.contactValue(), field("contacts", index, "contactValue")).trim();
            requireUnique(seenContactKeys, type + "\u0000" + value, field("contacts", index, "contactValue"),
                    "duplicates another contact type/value in this request");
        }
    }

    private static void validateLinks(List<LinkWriteRequest> links) {
        Set<String> seenLinkKeys = new HashSet<>();
        for (int index = 0; index < nullSafe(links).size(); index++) {
            LinkWriteRequest link = nullSafe(links).get(index);
            requireItem(link, "links", index);
            String type = normalizeRequired(link.linkType(), field("links", index, "linkType"));
            String url = ProfileWriteCanonicalizer.canonicalUrl(
                    requireText(link.url(), field("links", index, "url"))
            );
            requireUnique(seenLinkKeys, type + "\u0000" + url, field("links", index, "url"),
                    "duplicates another link type/url in this request");
        }
    }

    private static void validateSkills(List<SkillWriteRequest> skills) {
        Set<String> seenNormalizedSkills = new HashSet<>();
        for (int index = 0; index < nullSafe(skills).size(); index++) {
            SkillWriteRequest skill = nullSafe(skills).get(index);
            requireItem(skill, "skills", index);
            requireText(skill.skill(), field("skills", index, "skill"));
            requireNonNegative(skill.displayOrder(), field("skills", index, "displayOrder"));
            String normalized = skill.normalizedSkill() == null
                    ? normalizeRequired(skill.skill(), field("skills", index, "skill"))
                    : normalizeRequired(skill.normalizedSkill(), field("skills", index, "normalizedSkill"));
            requireUnique(seenNormalizedSkills, normalized, field("skills", index, "normalizedSkill"),
                    "duplicates another normalized skill in this request");
        }
    }

    private static void validateLanguages(List<LanguageWriteRequest> languages) {
        Set<String> seenNormalizedLanguages = new HashSet<>();
        for (int index = 0; index < nullSafe(languages).size(); index++) {
            LanguageWriteRequest language = nullSafe(languages).get(index);
            requireItem(language, "languages", index);
            requireText(language.language(), field("languages", index, "language"));
            requireNonNegative(language.displayOrder(), field("languages", index, "displayOrder"));
            String normalized = language.normalizedLanguage() == null
                    ? normalizeRequired(language.language(), field("languages", index, "language"))
                    : normalizeRequired(language.normalizedLanguage(), field("languages", index, "normalizedLanguage"));
            requireUnique(seenNormalizedLanguages, normalized, field("languages", index, "normalizedLanguage"),
                    "duplicates another normalized language in this request");
        }
    }

    private static void validateEducation(List<EducationWriteRequest> education) {
        Set<String> seenEducationKeys = new HashSet<>();
        for (int index = 0; index < nullSafe(education).size(); index++) {
            EducationWriteRequest item = nullSafe(education).get(index);
            requireItem(item, "education", index);
            requireDateRange(item.startDate(), item.endDate(), field("education", index, "endDate"));
            requireUnique(seenEducationKeys, key(
                    optionalNormalized(item.institution()),
                    optionalNormalized(item.degree()),
                    optionalNormalized(item.field()),
                    String.valueOf(item.startDate()),
                    String.valueOf(item.endDate())
            ), field("education", index, "institution"), "duplicates another education entry in this request");
        }
    }

    private static void validateExperiences(List<ExperienceWriteRequest> experiences) {
        Set<String> seenExperienceKeys = new HashSet<>();
        for (int index = 0; index < nullSafe(experiences).size(); index++) {
            ExperienceWriteRequest item = nullSafe(experiences).get(index);
            requireItem(item, "experiences", index);
            requireNonNegative(item.displayOrder(), field("experiences", index, "displayOrder"));
            requireDateRange(item.startDate(), item.endDate(), field("experiences", index, "endDate"));
            requireUnique(seenExperienceKeys, key(
                    optionalNormalized(item.company()),
                    optionalNormalized(item.title()),
                    String.valueOf(item.startDate()),
                    String.valueOf(item.endDate())
            ), field("experiences", index, "company"), "duplicates another experience entry in this request");
        }
    }

    private static void validateProjects(List<ProjectWriteRequest> projects) {
        Set<String> seenProjectKeys = new HashSet<>();
        for (int index = 0; index < nullSafe(projects).size(); index++) {
            ProjectWriteRequest project = nullSafe(projects).get(index);
            requireItem(project, "projects", index);
            requireNonNegative(project.displayOrder(), field("projects", index, "displayOrder"));
            requireUnique(seenProjectKeys, key(
                    optionalNormalized(project.name()),
                    optionalTrimmed(project.url())
            ), field("projects", index, "name"), "duplicates another project entry in this request");
            validateTechnologies(project.technologies(), index);
        }
    }

    private static void validateTechnologies(List<ProjectTechnologyWriteRequest> technologies, int projectIndex) {
        Set<String> seenNormalizedTechnologies = new HashSet<>();
        for (int index = 0; index < nullSafe(technologies).size(); index++) {
            ProjectTechnologyWriteRequest technology = nullSafe(technologies).get(index);
            String collection = "projects[" + projectIndex + "].technologies";
            requireItem(technology, collection, index);
            requireText(technology.technology(), field(collection, index, "technology"));
            requireNonNegative(technology.displayOrder(), field(collection, index, "displayOrder"));
            String normalized = technology.normalizedTechnology() == null
                    ? normalizeRequired(technology.technology(), field(collection, index, "technology"))
                    : normalizeRequired(technology.normalizedTechnology(), field(collection, index, "normalizedTechnology"));
            requireUnique(seenNormalizedTechnologies, normalized, field(collection, index, "normalizedTechnology"),
                    "duplicates another project technology in this request");
        }
    }

    private static void requireItem(Object item, String collection, int index) {
        if (item == null) {
            throw invalid(collection + "[" + index + "]", "must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "must not be blank");
        }
        return value;
    }

    private static String normalizeRequired(String value, String field) {
        return requireText(value, field).trim().toLowerCase(Locale.ROOT);
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw invalid(field, "must be greater than or equal to 0");
        }
    }

    private static void requireDateRange(LocalDate startDate, LocalDate endDate, String field) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw invalid(field, "must not be before startDate");
        }
    }

    private static void requireUnique(Set<String> seen, String key, String field, String reason) {
        if (!seen.add(key)) {
            throw invalid(field, reason);
        }
    }

    private static String field(String collection, int index, String field) {
        return collection + "[" + index + "]." + field;
    }

    private static String key(String... parts) {
        return String.join("\u0000", parts);
    }

    private static String optionalNormalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String optionalTrimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static ApplicationException invalid(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid profile write request",
                java.util.Map.of("field", field, "reason", reason),
                null
        );
    }
}
