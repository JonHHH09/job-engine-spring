package org.instruct.jobenginespring.application.document;

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
        appendCanadianContactLine(body, profile, aggregate.contacts(), aggregate.links());
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

    private static void appendCanadianContactLine(
            StringBuilder body,
            UserProfile profile,
            List<ProfileContact> contacts,
            List<ProfileLink> links
    ) {
        List<String> headerItems = new java.util.ArrayList<>();
        headerItems.add("Email: " + profile.email().strip());
        contacts.stream()
                .map(contact -> labelValue(contact.label(), contact.contactType(), contact.contactValue()))
                .forEach(headerItems::add);
        links.stream()
                .map(link -> labelValue(link.label(), link.linkType(), link.url()))
                .forEach(headerItems::add);
        appendLine(body, String.join(" | ", headerItems));
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

        List<String> sentences = splitSentences(lines.isEmpty() ? description.strip() : lines.getFirst());
        return limitBullets(sentences.isEmpty() ? lines : sentences, limit);
    }

    private static List<String> splitSentences(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CANADA);
        iterator.setText(text.strip());
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).strip();
            if (hasText(sentence)) {
                sentences.add(sentence);
            }
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
