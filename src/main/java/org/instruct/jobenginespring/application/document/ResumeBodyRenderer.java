package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.resume.OfflineFrenchResumeTranslator;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;

import java.text.BreakIterator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class ResumeBodyRenderer {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int CANADIAN_EXPERIENCE_BULLET_LIMIT = 3;

    private ResumeBodyRenderer() {
    }

    static String renderMasterResume(ProfileAggregate aggregate) {
        StringBuilder body = new StringBuilder();
        UserProfile profile = aggregate.profile();
        appendLine(body, profile.fullName());
        appendLine(body, profile.email());
        appendContacts(body, aggregate.contacts());
        appendLinks(body, aggregate.links());
        appendBlank(body);
        appendSection(body, "SUMMARY");
        appendLine(body, defaultText(profile.summary(), "Profile summary not provided."));
        appendBlank(body);
        appendSkills(body, aggregate.skills(), "SKILLS");
        appendLanguages(body, aggregate.languages());
        appendExperiencesByDisplayOrder(body, aggregate.experiences(), "EXPERIENCE");
        appendProjects(body, aggregate.projects());
        appendEducation(body, aggregate.education());
        return body.toString().strip();
    }

    static String renderCanadianResume(ProfileAggregate aggregate) {
        StringBuilder body = new StringBuilder();
        UserProfile profile = aggregate.profile();

        // Canadian resume rendering uses only normalized profile fields that are appropriate
        // for an applicant-facing resume. It deliberately avoids photos, SIN, references,
        // and protected personal details; those fields are not part of this profile schema.
        appendCanadianContactBlock(body, profile, aggregate.contacts(), aggregate.links());
        appendBlank(body);

        // Keep the section order aligned with the current Canadian resume variant:
        // concise professional summary, scannable technical skills, reverse-chronological
        // experience, education, and languages. Projects intentionally stay out of this
        // variant so education remains visible in the generated resume.
        appendSection(body, "PROFESSIONAL SUMMARY");
        appendLine(body, defaultText(profile.summary(), "Profile summary not provided."));
        appendBlank(body);
        appendSkills(body, aggregate.skills(), "TECHNICAL SKILLS");
        appendExperiencesReverseChronological(body, aggregate.experiences(), "PROFESSIONAL EXPERIENCE");
        appendEducation(body, aggregate.education());
        appendLanguages(body, aggregate.languages());
        return body.toString().strip();
    }

    static String renderCanadianFrenchResume(
            ProfileAggregate aggregate,
            OfflineFrenchResumeTranslator translator
    ) {
        StringBuilder body = new StringBuilder();
        UserProfile profile = aggregate.profile();
        List<String> protectedTerms = canadianFrenchProtectedTerms(aggregate);

        appendCanadianFrenchContactBlock(body, profile, aggregate.contacts(), aggregate.links(), translator, protectedTerms);
        appendBlank(body);
        appendSection(body, "PROFIL PROFESSIONNEL");
        appendLine(body, hasText(profile.summary())
                ? translator.translateText(profile.summary(), protectedTerms)
                : "Résumé du profil non fourni.");
        appendBlank(body);
        appendCanadianFrenchSkills(body, aggregate.skills(), translator);
        appendCanadianFrenchExperiences(body, aggregate.experiences(), translator, protectedTerms);
        appendCanadianFrenchEducation(body, aggregate.education(), translator, protectedTerms);
        appendCanadianFrenchLanguages(body, aggregate.languages(), translator);
        return body.toString().strip();
    }

    private static void appendCanadianContactBlock(
            StringBuilder body,
            UserProfile profile,
            List<ProfileContact> contacts,
            List<ProfileLink> links
    ) {
        List<String> contactItems = new java.util.ArrayList<>();
        contactItems.add(profile.email().strip());
        contacts.stream()
                .filter(contact -> !isEmailContact(contact))
                .map(contact -> labelValue(contact.label(), contact.contactType(), contact.contactValue()))
                .forEach(contactItems::add);
        appendLine(body, "Contact: " + String.join(" | ", contactItems));

        String professionalLinks = links.stream()
                .map(link -> labelValue(link.label(), link.linkType(), link.url()))
                .collect(Collectors.joining(" | "));
        if (hasText(professionalLinks)) {
            appendLine(body, "Links: " + professionalLinks);
        }
    }

    private static void appendCanadianFrenchContactBlock(
            StringBuilder body,
            UserProfile profile,
            List<ProfileContact> contacts,
            List<ProfileLink> links,
            OfflineFrenchResumeTranslator translator,
            List<String> protectedTerms
    ) {
        List<String> contactItems = new ArrayList<>();
        contactItems.add(translator.translateOpaqueValue(profile.email().strip()));
        contacts.stream()
                .filter(contact -> !isEmailContact(contact))
                .map(contact -> translatedLabelValue(
                        contact.label(),
                        contact.contactType(),
                        translatedContactValue(contact, translator, protectedTerms),
                        translator
                ))
                .forEach(contactItems::add);
        appendLine(body, translator.translateLabel("Contact") + ": " + String.join(" | ", contactItems));

        String professionalLinks = links.stream()
                .map(link -> translatedLabelValue(
                        link.label(),
                        link.linkType(),
                        translator.translateOpaqueValue(link.url()),
                        translator
                ))
                .collect(Collectors.joining(" | "));
        if (hasText(professionalLinks)) {
            appendLine(body, translator.translateLabel("Links") + ": " + professionalLinks);
        }
    }

    private static String translatedContactValue(
            ProfileContact contact,
            OfflineFrenchResumeTranslator translator,
            List<String> protectedTerms
    ) {
        String label = defaultText(contact.label(), contact.contactType());
        String type = defaultText(contact.contactType(), contact.label());
        String identity = (defaultText(label, "") + " " + defaultText(type, "")).toLowerCase(Locale.ROOT);
        if (identity.contains("location") || identity.contains("address")) {
            return translator.translateText(contact.contactValue(), protectedTerms);
        }
        return translator.translateOpaqueValue(contact.contactValue());
    }

    private static String translatedLabelValue(
            String label,
            String type,
            String value,
            OfflineFrenchResumeTranslator translator
    ) {
        String resolvedLabel = hasText(label) ? label.strip() : type.strip();
        return translator.translateLabel(resolvedLabel) + ": " + value.strip();
    }

    private static void appendCanadianFrenchSkills(
            StringBuilder body,
            List<ProfileSkill> skills,
            OfflineFrenchResumeTranslator translator
    ) {
        if (skills.isEmpty()) {
            return;
        }
        appendSection(body, "COMPÉTENCES TECHNIQUES");
        Map<String, List<ProfileSkill>> byCategory = skills.stream()
                .sorted(Comparator.comparingInt(ProfileSkill::displayOrder).thenComparing(ProfileSkill::skill))
                .collect(Collectors.groupingBy(
                        skill -> defaultText(skill.category(), "General"),
                        java.util.LinkedHashMap::new,
                        Collectors.toList()
                ));
        byCategory.forEach((category, categorySkills) -> appendLine(
                body,
                translator.translateLabel(category) + ": " + translator.translateTechnologyList(categorySkills.stream()
                        .map(ProfileSkill::skill)
                        .collect(Collectors.joining(", ")))
        ));
        appendBlank(body);
    }

    private static void appendCanadianFrenchExperiences(
            StringBuilder body,
            List<Experience> experiences,
            OfflineFrenchResumeTranslator translator,
            List<String> protectedTerms
    ) {
        if (experiences.isEmpty()) {
            return;
        }
        appendSection(body, "EXPÉRIENCE PROFESSIONNELLE");
        experiences.stream()
                .sorted(Comparator.comparing(ResumeBodyRenderer::experienceSortDate, Comparator.reverseOrder())
                        .thenComparing(Experience::displayOrder)
                        .thenComparing(Experience::company, Comparator.nullsLast(String::compareTo)))
                .forEach(experience -> {
                    appendLine(body, translator.translateText(defaultText(experience.title(), "Role"), protectedTerms)
                            + " | " + defaultText(experience.company(), "Entreprise"));
                    appendLine(body, periodFrench(experience.startDate(), experience.endDate())
                            + optionalTranslatedLocation(experience.location(), translator, protectedTerms));
                    if (hasText(experience.description())) {
                        experienceBullets(experience.description(), CANADIAN_EXPERIENCE_BULLET_LIMIT)
                                .forEach(bullet -> appendLine(body, "- " + translator.translateText(bullet, protectedTerms)));
                    }
                    appendBlank(body);
                });
    }

    private static void appendCanadianFrenchEducation(
            StringBuilder body,
            List<Education> education,
            OfflineFrenchResumeTranslator translator,
            List<String> protectedTerms
    ) {
        if (education.isEmpty()) {
            return;
        }
        appendSection(body, "FORMATION");
        education.stream()
                .sorted(Comparator.comparing(Education::endDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(item -> {
                    appendLine(body, translator.translateText(defaultText(item.degree(), "Diplôme"), protectedTerms)
                            + optionalTranslatedField(item.field(), translator, protectedTerms));
                    appendLine(body, defaultText(item.institution(), "Établissement")
                            + optionalTranslatedLocation(item.location(), translator, protectedTerms));
                    appendLine(body, periodFrench(item.startDate(), item.endDate()));
                    if (hasText(item.relevantFocus())) {
                        appendLine(body, "- " + translator.translateText(item.relevantFocus(), protectedTerms));
                    }
                    appendBlank(body);
                });
    }

    private static void appendCanadianFrenchLanguages(
            StringBuilder body,
            List<ProfileLanguage> languages,
            OfflineFrenchResumeTranslator translator
    ) {
        if (languages.isEmpty()) {
            return;
        }
        appendSection(body, "LANGUES");
        appendLine(body, languages.stream()
                .sorted(Comparator.comparingInt(ProfileLanguage::displayOrder).thenComparing(ProfileLanguage::language))
                .map(language -> translator.translateText(language.language())
                        + translatedOptionalSuffix(language.proficiency(), translator))
                .collect(Collectors.joining(", ")));
        appendBlank(body);
    }

    private static String translatedOptionalSuffix(String text, OfflineFrenchResumeTranslator translator) {
        return hasText(text) ? " (" + translator.translateLabel(text) + ")" : "";
    }

    private static String optionalTranslatedLocation(
            String location,
            OfflineFrenchResumeTranslator translator,
            List<String> protectedTerms
    ) {
        return hasText(location) ? " | " + translator.translateText(location, protectedTerms) : "";
    }

    private static String optionalTranslatedField(
            String field,
            OfflineFrenchResumeTranslator translator,
            List<String> protectedTerms
    ) {
        return hasText(field) ? ", " + translator.translateText(field, protectedTerms) : "";
    }

    private static List<String> canadianFrenchProtectedTerms(ProfileAggregate aggregate) {
        List<String> terms = new ArrayList<>();
        terms.add(aggregate.profile().fullName());
        aggregate.experiences().stream().map(Experience::company).forEach(terms::add);
        aggregate.education().stream().map(Education::institution).forEach(terms::add);
        aggregate.projects().stream().map(ProfileProject::name).forEach(terms::add);
        aggregate.skills().stream().map(ProfileSkill::skill).forEach(terms::add);
        aggregate.projectTechnologies().stream().map(ProjectTechnology::technology).forEach(terms::add);
        aggregate.projects().stream()
                .flatMap(project -> project.technologies().stream())
                .map(ProjectTechnology::technology)
                .forEach(terms::add);
        return terms.stream()
                .filter(ResumeBodyRenderer::hasText)
                .map(String::strip)
                .distinct()
                .toList();
    }

    private static String periodFrench(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return "Dates non précisées";
        }
        String start = startDate == null ? "Inconnu" : MONTH_FORMATTER.format(startDate);
        String end = endDate == null ? "Présent" : MONTH_FORMATTER.format(endDate);
        return start + " - " + end;
    }

    private static boolean isEmailContact(ProfileContact contact) {
        return containsEmailSignal(contact.contactType())
                || containsEmailSignal(contact.label())
                || containsEmailSignal(contact.contactValue());
    }

    private static boolean containsEmailSignal(String text) {
        if (!hasText(text)) {
            return false;
        }
        String stripped = text.strip();
        return stripped.toLowerCase(Locale.ROOT).contains("email") || stripped.contains("@");
    }

    private static void appendContacts(StringBuilder body, List<ProfileContact> contacts) {
        if (contacts.isEmpty()) {
            return;
        }
        appendLine(body, contacts.stream()
                .map(contact -> labelValue(contact.label(), contact.contactType(), contact.contactValue()))
                .collect(Collectors.joining(" | ")));
    }

    private static void appendLinks(StringBuilder body, List<ProfileLink> links) {
        if (links.isEmpty()) {
            return;
        }
        appendLine(body, links.stream()
                .map(link -> labelValue(link.label(), link.linkType(), link.url()))
                .collect(Collectors.joining(" | ")));
    }

    private static void appendSkills(StringBuilder body, List<ProfileSkill> skills, String heading) {
        if (skills.isEmpty()) {
            return;
        }
        appendSection(body, heading);
        Map<String, List<ProfileSkill>> byCategory = skills.stream()
                .sorted(Comparator.comparingInt(ProfileSkill::displayOrder).thenComparing(ProfileSkill::skill))
                .collect(Collectors.groupingBy(skill -> defaultText(skill.category(), "General"), java.util.LinkedHashMap::new, Collectors.toList()));
        byCategory.forEach((category, categorySkills) -> appendLine(body, category + ": " + categorySkills.stream()
                .map(ProfileSkill::skill)
                .collect(Collectors.joining(", "))));
        appendBlank(body);
    }

    private static void appendLanguages(StringBuilder body, List<ProfileLanguage> languages) {
        if (languages.isEmpty()) {
            return;
        }
        appendSection(body, "LANGUAGES");
        appendLine(body, languages.stream()
                .sorted(Comparator.comparingInt(ProfileLanguage::displayOrder).thenComparing(ProfileLanguage::language))
                .map(language -> language.language() + optionalSuffix(language.proficiency()))
                .collect(Collectors.joining(", ")));
        appendBlank(body);
    }

    private static void appendExperiencesByDisplayOrder(StringBuilder body, List<Experience> experiences, String heading) {
        appendExperiences(
                body,
                experiences.stream()
                        .sorted(Comparator.comparingInt(Experience::displayOrder).thenComparing(Experience::company, Comparator.nullsLast(String::compareTo)))
                        .toList(),
                heading,
                false
        );
    }

    private static void appendExperiencesReverseChronological(StringBuilder body, List<Experience> experiences, String heading) {
        appendExperiences(
                body,
                experiences.stream()
                        .sorted(Comparator.comparing(ResumeBodyRenderer::experienceSortDate, Comparator.reverseOrder())
                                .thenComparing(Experience::displayOrder)
                                .thenComparing(Experience::company, Comparator.nullsLast(String::compareTo)))
                        .toList(),
                heading,
                true
        );
    }

    private static LocalDate experienceSortDate(Experience experience) {
        if (experience.endDate() != null) {
            return experience.endDate();
        }
        if (experience.startDate() != null) {
            return experience.startDate();
        }
        return LocalDate.MIN;
    }

    private static void appendExperiences(StringBuilder body, List<Experience> experiences, String heading, boolean splitDescriptionBullets) {
        if (experiences.isEmpty()) {
            return;
        }
        appendSection(body, heading);
        experiences.forEach(experience -> {
            appendLine(body, defaultText(experience.title(), "Role") + " | " + defaultText(experience.company(), "Company"));
            appendLine(body, period(experience.startDate(), experience.endDate()) + optionalLocation(experience.location()));
            if (hasText(experience.description())) {
                if (splitDescriptionBullets) {
                    experienceBullets(experience.description(), CANADIAN_EXPERIENCE_BULLET_LIMIT)
                            .forEach(bullet -> appendLine(body, "- " + bullet));
                } else {
                    appendLine(body, "- " + experience.description().strip());
                }
            }
            appendBlank(body);
        });
    }

    private static List<String> experienceBullets(String description, int limit) {
        if (!hasText(description) || limit <= 0) {
            return List.of();
        }

        List<String> lines = description.strip().lines()
                .map(String::strip)
                .filter(ResumeBodyRenderer::hasText)
                .map(ResumeBodyRenderer::stripBulletPrefix)
                .filter(ResumeBodyRenderer::hasText)
                .toList();

        if (lines.size() > 1) {
            return limitBullets(lines, limit);
        }

        return limitBullets(splitSentences(lines.getFirst()), limit);
    }

    private static List<String> splitSentences(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        String strippedText = text.strip();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CANADA);
        iterator.setText(strippedText);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            sentences.add(strippedText.substring(start, end).strip());
        }
        return sentences;
    }

    private static String stripBulletPrefix(String text) {
        String stripped = text.strip();
        if (stripped.length() >= 2 && (stripped.startsWith("- ") || stripped.startsWith("* ") || stripped.startsWith("• "))) {
            return stripped.substring(2).strip();
        }
        return stripped.replaceFirst("^\\d+[.)]\\s+", "").strip();
    }

    private static List<String> limitBullets(List<String> bullets, int limit) {
        return bullets.stream()
                .map(String::strip)
                .filter(ResumeBodyRenderer::hasText)
                .limit(limit)
                .toList();
    }

    private static void appendProjects(StringBuilder body, List<ProfileProject> projects) {
        if (projects.isEmpty()) {
            return;
        }
        appendSection(body, "PROJECTS");
        projects.stream()
                .sorted(Comparator.comparingInt(ProfileProject::displayOrder).thenComparing(ProfileProject::name, Comparator.nullsLast(String::compareTo)))
                .forEach(project -> {
                    appendLine(body, defaultText(project.name(), "Project") + optionalUrl(project.url()));
                    String technologies = project.technologies().stream()
                            .sorted(Comparator.comparingInt(ProjectTechnology::displayOrder).thenComparing(ProjectTechnology::technology))
                            .map(ProjectTechnology::technology)
                            .collect(Collectors.joining(", "));
                    if (hasText(technologies)) {
                        appendLine(body, "Technologies: " + technologies);
                    }
                    if (hasText(project.description())) {
                        appendLine(body, "- " + project.description().strip());
                    }
                    appendBlank(body);
                });
    }

    private static void appendEducation(StringBuilder body, List<Education> education) {
        if (education.isEmpty()) {
            return;
        }
        appendSection(body, "EDUCATION");
        education.stream()
                .sorted(Comparator.comparing(Education::endDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(item -> {
                    appendLine(body, defaultText(item.degree(), "Education") + optionalField(item.field()));
                    appendLine(body, defaultText(item.institution(), "Institution") + optionalLocation(item.location()));
                    appendLine(body, period(item.startDate(), item.endDate()));
                    if (hasText(item.relevantFocus())) {
                        appendLine(body, "- " + item.relevantFocus().strip());
                    }
                    appendBlank(body);
                });
    }

    private static String labelValue(String label, String type, String value) {
        String resolvedLabel = hasText(label) ? label.strip() : type.strip();
        return resolvedLabel + ": " + value.strip();
    }

    private static String period(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return "Dates not provided";
        }
        String start = startDate == null ? "Unknown" : MONTH_FORMATTER.format(startDate);
        String end = endDate == null ? "Present" : MONTH_FORMATTER.format(endDate);
        return start + " - " + end;
    }

    private static String optionalSuffix(String text) {
        return hasText(text) ? " (" + text.strip() + ")" : "";
    }

    private static String optionalLocation(String location) {
        return hasText(location) ? " | " + location.strip() : "";
    }

    private static String optionalUrl(String url) {
        return hasText(url) ? " - " + url.strip() : "";
    }

    private static String optionalField(String field) {
        return hasText(field) ? ", " + field.strip() : "";
    }

    private static String defaultText(String text, String fallback) {
        return hasText(text) ? text.strip() : fallback;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static void appendSection(StringBuilder body, String title) {
        appendLine(body, title);
    }

    private static void appendLine(StringBuilder body, String line) {
        body.append(line).append('\n');
    }

    private static void appendBlank(StringBuilder body) {
        body.append('\n');
    }
}
