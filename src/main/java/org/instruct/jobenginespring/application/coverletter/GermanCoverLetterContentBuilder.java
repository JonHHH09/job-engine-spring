package org.instruct.jobenginespring.application.coverletter;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds conservative German prose from normalized profile/job facts without external providers. */
public final class GermanCoverLetterContentBuilder {

    private static final int MAX_CONTACT_FIELDS = 6;
    private static final int MAX_MATCHED_SKILLS = 5;

    public GermanCoverLetterContent build(ProfileAggregate profile, JobAggregate job) {
        ProfileAggregate safeProfile = Objects.requireNonNull(profile, "profile must not be null");
        JobAggregate safeJob = Objects.requireNonNull(job, "job must not be null");
        JobPosting posting = safeJob.job();
        String jobTitle = posting.title().strip();
        String company = optional(posting.company());
        String subject = "Bewerbung als " + jobTitle + (company == null ? "" : " bei " + company);

        List<GermanCoverLetterContent.PersonalField> personalFields = personalFields(safeProfile);
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("Mit großem Interesse bewerbe ich mich als " + jobTitle
                + (company == null ? "" : " bei " + company) + ".");
        evidenceParagraph(safeProfile, paragraphs);
        List<String> skills = relevantSkills(safeProfile, safeJob);
        if (!skills.isEmpty()) {
            paragraphs.add("Für diese Position bringe ich insbesondere Kenntnisse in "
                    + String.join(", ", skills) + " mit.");
        }
        paragraphs.add("Gern erläutere ich Ihnen in einem persönlichen Gespräch, wie ich meine Erfahrung in Ihr Team einbringen kann.");

        return new GermanCoverLetterContent(
                safeProfile.profile().fullName(),
                personalFields,
                company,
                optional(posting.location()),
                jobTitle,
                subject,
                "Sehr geehrte Damen und Herren,",
                paragraphs,
                "Mit freundlichen Grüßen,",
                safeProfile.profile().fullName()
        );
    }

    private static List<GermanCoverLetterContent.PersonalField> personalFields(ProfileAggregate profile) {
        List<GermanCoverLetterContent.PersonalField> fields = new ArrayList<>();
        fields.add(new GermanCoverLetterContent.PersonalField("E-Mail", profile.profile().email()));
        profile.contacts().stream()
                .filter(contact -> !isEmail(contact))
                .sorted(Comparator.comparing(ProfileContact::contactType))
                .limit(MAX_CONTACT_FIELDS - 1L)
                .forEach(contact -> fields.add(new GermanCoverLetterContent.PersonalField(
                        firstText(contact.label(), contact.contactType()), contact.contactValue())));
        profile.links().stream()
                .sorted(Comparator.comparing(ProfileLink::linkType))
                .limit(MAX_CONTACT_FIELDS - fields.size())
                .forEach(link -> fields.add(new GermanCoverLetterContent.PersonalField(
                        firstText(link.label(), link.linkType()), link.url())));
        return List.copyOf(fields);
    }

    private static void evidenceParagraph(ProfileAggregate profile, List<String> paragraphs) {
        List<Experience> experiences = profile.experiences().stream()
                .sorted(Comparator.comparing(GermanCoverLetterContentBuilder::experienceDate, Comparator.reverseOrder()))
                .toList();
        if (!experiences.isEmpty()) {
            Experience experience = experiences.getFirst();
            paragraphs.add("In meiner bisherigen Tätigkeit als " + experience.title()
                    + " bei " + experience.company() + " habe ich praktische Erfahrung gesammelt.");
            return;
        }
        List<Education> education = profile.education();
        if (!education.isEmpty()) {
            Education item = education.getFirst();
            paragraphs.add("Mein Abschluss " + item.degree() + " an " + item.institution()
                    + " bildet eine fachliche Grundlage für diese Aufgabe.");
        }
    }

    private static List<String> relevantSkills(ProfileAggregate profile, JobAggregate job) {
        Set<String> jobTokens = tokens(job.job().title() + " " + job.job().description()
                + " " + job.skills().stream().map(skill -> skill.skill()).collect(Collectors.joining(" ")));
        List<ProfileSkill> ranked = profile.skills().stream()
                .sorted(Comparator
                        .comparingInt((ProfileSkill skill) -> -overlap(skill.skill(), jobTokens))
                        .thenComparingInt(ProfileSkill::displayOrder)
                        .thenComparing(ProfileSkill::skill))
                .toList();
        List<ProfileSkill> matched = ranked.stream().filter(skill -> overlap(skill.skill(), jobTokens) > 0).toList();
        List<ProfileSkill> selected = matched.isEmpty() ? ranked : matched;
        return selected.stream().limit(MAX_MATCHED_SKILLS).map(ProfileSkill::skill).toList();
    }

    private static int overlap(String value, Set<String> jobTokens) {
        return tokens(value).stream().mapToInt(token -> jobTokens.contains(token) ? 1 : 0).sum();
    }

    private static Set<String> tokens(String value) {
        return java.util.Arrays.stream(Objects.toString(value, "").toLowerCase(Locale.ROOT).split("[^a-z0-9+#./]+"))
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static java.time.LocalDate experienceDate(Experience experience) {
        return experience.endDate() == null ? Objects.requireNonNullElse(experience.startDate(), java.time.LocalDate.MIN) : experience.endDate();
    }

    private static boolean isEmail(ProfileContact contact) {
        return contact.contactValue().contains("@")
                || Objects.toString(contact.contactType(), "").toLowerCase(Locale.ROOT).contains("mail");
    }


    private static String firstText(String first, String requiredSecond) {
        return hasText(first) ? first.strip() : requiredSecond.strip();
    }

    private static String optional(String value) {
        return hasText(value) ? value.strip() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
