package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;

import java.text.BreakIterator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds English structured Lebenslauf content tailored to a job from profile data.
 */
public final class GermanLebenslaufContentBuilder {

    private static final int MAX_EXPERIENCE_BULLETS = 5;
    private static final int MAX_ADDITIONAL_PROJECTS = 2;
    private static final int MAX_SELECTED_SKILLS = 24;

    private GermanLebenslaufContentBuilder() {
    }

    public static StructuredResumeContent buildEnglish(
            ProfileAggregate profile,
            JobAggregate job,
            ProfilePersonalDetails personalDetails
    ) {
        return buildEnglish(profile, job, personalDetails, false);
    }

    public static StructuredResumeContent buildEnglish(
            ProfileAggregate profile,
            JobAggregate job,
            ProfilePersonalDetails personalDetails,
            boolean includeProjects
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(job, "job must not be null");

        Set<String> jobTokens = jobTokens(job);
        List<StructuredResumeContent.PersonalField> personal = personalFields(profile, personalDetails);
        List<StructuredResumeContent.ExperienceEntry> experiences = experiences(profile.experiences(), jobTokens);
        List<StructuredResumeContent.EducationEntry> education = education(profile.education());
        List<StructuredResumeContent.SkillGroup> skills = skillGroups(profile.skills(), jobTokens);
        List<StructuredResumeContent.LanguageEntry> languages = languages(profile.languages());
        List<StructuredResumeContent.AdditionalEntry> additional = includeProjects
                ? additional(profile.projects(), jobTokens)
                : List.of();

        return new StructuredResumeContent(
                profile.profile().fullName(),
                ResumeVariant.LANGUAGE_EN,
                profile.profile().summary(),
                personal,
                experiences,
                education,
                skills,
                languages,
                additional
        );
    }

    private static List<StructuredResumeContent.PersonalField> personalFields(
            ProfileAggregate profile,
            ProfilePersonalDetails personalDetails
    ) {
        List<StructuredResumeContent.PersonalField> fields = new ArrayList<>();
        fields.add(new StructuredResumeContent.PersonalField("Email", profile.profile().email()));

        profile.contacts().stream()
                .sorted(Comparator.comparing(ProfileContact::contactType, Comparator.nullsLast(String::compareTo)))
                .forEach(contact -> {
                    if (isEmailLike(contact)) {
                        return;
                    }
                    String label = hasText(contact.label()) ? contact.label() : contact.contactType();
                    fields.add(new StructuredResumeContent.PersonalField(label, contact.contactValue()));
                });

        profile.links().stream()
                .sorted(Comparator.comparing(ProfileLink::linkType, Comparator.nullsLast(String::compareTo)))
                .forEach(link -> {
                    String label = hasText(link.label()) ? link.label() : link.linkType();
                    fields.add(new StructuredResumeContent.PersonalField(label, link.url()));
                });

        if (personalDetails != null) {
            if (personalDetails.dateOfBirth() != null) {
                fields.add(new StructuredResumeContent.PersonalField("Date of birth", personalDetails.dateOfBirth().toString()));
            }
            if (hasText(personalDetails.nationality())) {
                fields.add(new StructuredResumeContent.PersonalField("Nationality", personalDetails.nationality()));
            }
            // Photo is optional and never forced into text body; PDF layer may embed later if document exists.
        }
        return fields;
    }

    private static List<StructuredResumeContent.ExperienceEntry> experiences(
            List<Experience> experiences,
            Set<String> jobTokens
    ) {
        return experiences.stream()
                .sorted(Comparator.comparing(GermanLebenslaufContentBuilder::experienceSortDate, Comparator.reverseOrder())
                        .thenComparing(Experience::displayOrder))
                .map(experience -> new StructuredResumeContent.ExperienceEntry(
                        defaultText(experience.title(), "Role"),
                        defaultText(experience.company(), "Company"),
                        experience.location(),
                        experience.startDate(),
                        experience.endDate(),
                        prioritizeBullets(splitBullets(experience.description()), jobTokens, MAX_EXPERIENCE_BULLETS)
                ))
                .toList();
    }

    private static List<StructuredResumeContent.EducationEntry> education(List<Education> education) {
        return education.stream()
                .sorted(Comparator.comparing(Education::endDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(item -> {
                    List<String> bullets = new ArrayList<>();
                    if (hasText(item.field())) {
                        bullets.add("Field of study: " + item.field().strip());
                    }
                    if (hasText(item.relevantFocus())) {
                        bullets.add(item.relevantFocus().strip());
                    }
                    if (hasText(item.location())) {
                        bullets.add("International degree program (" + item.location().strip() + ").");
                    }
                    return new StructuredResumeContent.EducationEntry(
                            defaultText(item.degree(), "Degree"),
                            defaultText(item.institution(), "Institution"),
                            item.location(),
                            item.startDate(),
                            item.endDate(),
                            bullets
                    );
                })
                .toList();
    }

    private static List<StructuredResumeContent.SkillGroup> skillGroups(List<ProfileSkill> skills, Set<String> jobTokens) {
        List<ProfileSkill> ranked = skills.stream()
                .sorted(Comparator
                        .comparingInt((ProfileSkill skill) -> -skillOverlapScore(skill.skill(), jobTokens))
                        .thenComparingInt(skill -> -categoryPriority(skill.category()))
                        .thenComparingInt(ProfileSkill::displayOrder)
                        .thenComparing(ProfileSkill::skill))
                .toList();
        List<ProfileSkill> relevant = ranked.stream()
                .filter(skill -> skillOverlapScore(skill.skill(), jobTokens) > 0)
                .toList();
        List<ProfileSkill> selected = relevant.isEmpty() ? ranked : relevant;
        Map<String, List<ProfileSkill>> byCategory = selected.stream()
                .limit(MAX_SELECTED_SKILLS)
                .collect(Collectors.groupingBy(
                        skill -> defaultText(skill.category(), "Technical"),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<StructuredResumeContent.SkillGroup> groups = new ArrayList<>();
        byCategory.forEach((category, categorySkills) -> groups.add(new StructuredResumeContent.SkillGroup(
                category,
                categorySkills.stream().map(ProfileSkill::skill).toList()
        )));
        return groups;
    }

    private static int categoryPriority(String category) {
        String normalized = Objects.toString(category, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("machine learning") || normalized.contains("mlops")) {
            return 5;
        }
        if (normalized.contains("architecture")) {
            return 4;
        }
        if (normalized.contains("cloud") || normalized.contains("ops")) {
            return 3;
        }
        if (normalized.contains("backend") || normalized.contains("data")) {
            return 2;
        }
        if (normalized.contains("security")) {
            return 1;
        }
        return 0;
    }

    private static List<StructuredResumeContent.LanguageEntry> languages(List<ProfileLanguage> languages) {
        return languages.stream()
                .sorted(Comparator.comparingInt(ProfileLanguage::displayOrder).thenComparing(ProfileLanguage::language))
                .map(language -> new StructuredResumeContent.LanguageEntry(language.language(), language.proficiency()))
                .toList();
    }

    private static List<StructuredResumeContent.AdditionalEntry> additional(
            List<ProfileProject> projects,
            Set<String> jobTokens
    ) {
        return projects.stream()
                .sorted(Comparator
                        .comparingInt((ProfileProject project) -> -projectOverlapScore(project, jobTokens))
                        .thenComparingInt(ProfileProject::displayOrder))
                .limit(MAX_ADDITIONAL_PROJECTS)
                .map(project -> {
                    List<String> bullets = new ArrayList<>();
                    if (hasText(project.description())) {
                        bullets.addAll(prioritizeBullets(splitBullets(project.description()), jobTokens, 3));
                    }
                    String technologies = project.technologies().stream()
                            .map(technology -> technology.technology())
                            .filter(GermanLebenslaufContentBuilder::hasText)
                            .collect(Collectors.joining(", "));
                    if (hasText(technologies)) {
                        bullets.add("Technologies: " + technologies);
                    }
                    return new StructuredResumeContent.AdditionalEntry(
                            defaultText(project.name(), "Project"),
                            project.url(),
                            bullets
                    );
                })
                .toList();
    }

    private static Set<String> jobTokens(JobAggregate job) {
        JobPosting posting = job.job();
        StringBuilder text = new StringBuilder();
        append(text, posting.title());
        append(text, posting.company());
        append(text, posting.description());
        append(text, posting.experienceRequirement());
        append(text, posting.seniority());
        for (JobSkill skill : job.skills()) {
            append(text, skill.skill());
        }
        return tokenize(text.toString());
    }

    private static void append(StringBuilder builder, String value) {
        if (hasText(value)) {
            builder.append(' ').append(value);
        }
    }

    private static Set<String> tokenize(String text) {
        return java.util.Arrays.stream(Objects.toString(text, "").toLowerCase(Locale.ROOT).split("[^a-z0-9+#./]+"))
                .filter(token -> token.length() >= 3)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static int skillOverlapScore(String skill, Set<String> jobTokens) {
        return tokenize(skill).stream().mapToInt(token -> jobTokens.contains(token) ? 1 : 0).sum();
    }

    private static int projectOverlapScore(ProfileProject project, Set<String> jobTokens) {
        StringBuilder text = new StringBuilder();
        append(text, project.name());
        append(text, project.description());
        project.technologies().forEach(technology -> append(text, technology.technology()));
        return tokenize(text.toString()).stream().mapToInt(token -> jobTokens.contains(token) ? 1 : 0).sum();
    }

    private static List<String> splitBullets(String description) {
        if (!hasText(description)) {
            return List.of();
        }
        List<String> lines = description.strip().lines()
                .map(String::strip)
                .filter(GermanLebenslaufContentBuilder::hasText)
                .map(GermanLebenslaufContentBuilder::stripBulletPrefix)
                .filter(GermanLebenslaufContentBuilder::hasText)
                .toList();
        if (lines.size() > 1) {
            return lines;
        }
        return splitSentences(lines.getFirst());
    }

    private static List<String> splitSentences(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        iterator.setText(text);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            sentences.add(text.substring(start, end).strip());
        }
        return sentences.stream().filter(GermanLebenslaufContentBuilder::hasText).toList();
    }

    private static List<String> prioritizeBullets(List<String> bullets, Set<String> jobTokens, int limit) {
        return bullets.stream()
                .sorted(Comparator.comparingInt((String bullet) -> -skillOverlapScore(bullet, jobTokens)))
                .limit(limit)
                .toList();
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

    static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    static boolean isEmailLike(ProfileContact contact) {
        return containsEmailSignal(contact.contactValue()) || containsEmailSignal(contact.contactType());
    }

    static boolean containsEmailSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String stripped = text.strip().toLowerCase(Locale.ROOT);
        return stripped.contains("email") || stripped.contains("@");
    }

    static String stripBulletPrefix(String text) {
        String stripped = text.strip();
        if (stripped.startsWith("- ") || stripped.startsWith("* ") || stripped.startsWith("• ")) {
            return stripped.substring(2).strip();
        }
        return stripped.replaceFirst("^\\d+[.)]\\s+", "").strip();
    }

    private static String defaultText(String text, String fallback) {
        return hasText(text) ? text.strip() : fallback;
    }
}
