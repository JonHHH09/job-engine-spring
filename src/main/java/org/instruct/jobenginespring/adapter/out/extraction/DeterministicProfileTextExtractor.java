package org.instruct.jobenginespring.adapter.out.extraction;

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
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative deterministic fallback extractor for profile ingestion.
 *
 * <p>This intentionally extracts only obvious fields. It treats source text as untrusted data and never
 * interprets instructions embedded in the document.</p>
 */
@lombok.Generated
@Component
public class DeterministicProfileTextExtractor implements ProfileTextExtractor {

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?1[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?)\\d{3}[-.\\s]?\\d{4}(?!\\d)");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)>,]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINKEDIN = Pattern.compile("(?:https?://)?(?:www\\.)?linkedin\\.com/in/[^\\s)>,]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB = Pattern.compile("(?:https?://)?(?:www\\.)?github\\.com/[^\\s)>,]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_HEADER = Pattern.compile("^(summary|profile|professional summary|skills|technical skills|technologies|technology stack|languages|language skills|experience|work experience|projects|education)\\s*:?");
    private static final Pattern DATE_RANGE = Pattern.compile(
            "(?<start>(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)?\\s*\\d{4})\\s*(?:-|–|—|to)\\s*(?<end>present|current|(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)?\\s*\\d{4})",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> KNOWN_SKILLS = List.of(
            "kotlin multiplatform", "spring boot", "spring cloud", "spring ai", "testcontainers",
            "typescript", "javascript", "postgresql", "next.js", "pyside6", "sqlite", "python",
            "flyway", "docker", "kotlin", "react", "java", "jdbc", "mcp", "qt", "qml", "aws"
    );
    private static final List<String> HUMAN_LANGUAGES = List.of(
            "albanian", "english", "french", "spanish", "german", "italian", "portuguese", "arabic",
            "mandarin", "chinese", "japanese", "korean", "hindi", "russian", "ukrainian"
    );

    @Override
    public ProfileWriteRequest extractProfile(ProfileTextExtractionInput input) {
        if (input == null || input.text() == null || input.text().isBlank()) {
            throw invalid("text", "must not be blank");
        }
        String text = input.text();
        String email = firstMatch(EMAIL, text);
        if (email == null) {
            throw invalid("email", "could not be extracted from PDF text");
        }
        String fullName = firstLikelyName(text, email);
        if (fullName == null) {
            throw invalid("fullName", "could not be extracted from PDF text");
        }

        List<ContactWriteRequest> contacts = new ArrayList<>();
        contacts.add(new ContactWriteRequest(null, "email", email, "extracted resume email"));
        String phone = firstMatch(PHONE, text);
        if (phone != null) {
            contacts.add(new ContactWriteRequest(null, "phone", phone, "extracted resume phone"));
        }

        ResumeSections sections = sections(text);
        List<LinkWriteRequest> links = links(text);
        List<SkillWriteRequest> skills = skills(sections.skillsText());
        List<LanguageWriteRequest> languages = languages(sections.languagesText());
        List<EducationWriteRequest> education = education(sections.educationEntries());
        List<ExperienceWriteRequest> experiences = experiences(sections.experienceEntries());
        List<ProjectWriteRequest> projects = projects(sections.projectEntries());
        String summary = summary(text, sections.summaryText());
        return new ProfileWriteRequest(
                fullName,
                email,
                summary,
                contacts,
                links,
                skills,
                languages,
                education,
                experiences,
                projects
        );
    }

    private static String firstLikelyName(String text, String email) {
        for (String rawLine : text.lines().limit(12).toList()) {
            String line = rawLine.strip();
            if (line.isBlank() || line.contains("@") || line.toLowerCase(Locale.ROOT).contains("http")) {
                continue;
            }
            if (line.length() <= 80 && line.matches("[\\p{L}][\\p{L} .'-]+")) {
                return line;
            }
        }
        String localPart = email.substring(0, email.indexOf('@')).replaceAll("[._-]+", " ").strip();
        if (localPart.contains(" ")) {
            return titleCase(localPart);
        }
        return null;
    }

    private static List<LinkWriteRequest> links(String text) {
        Set<String> seen = new LinkedHashSet<>();
        List<LinkWriteRequest> links = new ArrayList<>();
        addLink(links, seen, "linkedin", firstMatch(LINKEDIN, text), "LinkedIn");
        addLink(links, seen, "github", firstMatch(GITHUB, text), "GitHub");
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String url = normalizeUrl(matcher.group());
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.contains("linkedin.com/in/") && !lower.contains("github.com/")) {
                addLink(links, seen, "website", url, "Website");
            }
        }
        return links;
    }

    private static void addLink(List<LinkWriteRequest> links, Set<String> seen, String type, String rawUrl, String label) {
        if (rawUrl == null) {
            return;
        }
        String url = normalizeUrl(rawUrl);
        String key = type + "\u0000" + url;
        if (seen.add(key)) {
            links.add(new LinkWriteRequest(null, type, url, label));
        }
    }

    private static List<SkillWriteRequest> skills(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<SkillWriteRequest> skills = new ArrayList<>();
        int displayOrder = 0;
        for (String skill : KNOWN_SKILLS) {
            if (containsPhrase(lower, skill)) {
                skills.add(new SkillWriteRequest(null, displayName(skill), skill, "extracted", displayOrder++));
            }
        }
        return skills;
    }

    private static List<LanguageWriteRequest> languages(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<LanguageWriteRequest> languages = new ArrayList<>();
        int displayOrder = 0;
        for (String language : HUMAN_LANGUAGES) {
            if (containsPhrase(lower, language)) {
                languages.add(new LanguageWriteRequest(null, displayName(language), language, null, displayOrder++));
            }
        }
        return languages;
    }

    private static ResumeSections sections(String text) {
        StringBuilder summary = new StringBuilder();
        StringBuilder skills = new StringBuilder();
        StringBuilder languages = new StringBuilder();
        List<List<String>> educationEntries = new ArrayList<>();
        List<List<String>> experienceEntries = new ArrayList<>();
        List<List<String>> projectEntries = new ArrayList<>();
        List<String> currentEntry = new ArrayList<>();
        String currentSection = "";
        for (String rawLine : text.lines().toList()) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                flushEntry(currentSection, currentEntry, educationEntries, experienceEntries, projectEntries);
                continue;
            }
            Matcher header = SECTION_HEADER.matcher(line.toLowerCase(Locale.ROOT));
            if (header.matches()) {
                flushEntry(currentSection, currentEntry, educationEntries, experienceEntries, projectEntries);
                currentSection = header.group(1);
                continue;
            }
            if (isSummarySection(currentSection)) {
                summary.append(line).append('\n');
            } else if (isSkillSection(currentSection)) {
                skills.append(line).append('\n');
            } else if (isLanguageSection(currentSection)) {
                languages.append(line).append('\n');
            } else if (isStructuredSection(currentSection)) {
                if (!currentEntry.isEmpty() && startsStructuredEntry(line)) {
                    flushEntry(currentSection, currentEntry, educationEntries, experienceEntries, projectEntries);
                }
                currentEntry.add(line);
            }
        }
        flushEntry(currentSection, currentEntry, educationEntries, experienceEntries, projectEntries);
        return new ResumeSections(summary.toString(), skills.toString(), languages.toString(),
                educationEntries, experienceEntries, projectEntries);
    }

    private static void flushEntry(
            String currentSection,
            List<String> currentEntry,
            List<List<String>> educationEntries,
            List<List<String>> experienceEntries,
            List<List<String>> projectEntries
    ) {
        if (currentEntry.isEmpty()) {
            return;
        }
        if (isEducationSection(currentSection)) {
            educationEntries.add(List.copyOf(currentEntry));
        } else if (isExperienceSection(currentSection)) {
            experienceEntries.add(List.copyOf(currentEntry));
        } else if (isProjectSection(currentSection)) {
            projectEntries.add(List.copyOf(currentEntry));
        }
        currentEntry.clear();
    }

    private static boolean startsStructuredEntry(String line) {
        return DATE_RANGE.matcher(line).find() || line.contains("|") || line.matches("^[\\p{L}0-9][\\p{L}0-9 .'-]+\\s+[-–—]\\s+.+");
    }

    private static boolean isSummarySection(String section) {
        return section.equals("summary") || section.equals("profile") || section.equals("professional summary");
    }

    private static boolean isSkillSection(String section) {
        return section.equals("skills") || section.equals("technical skills") || section.equals("technologies")
                || section.equals("technology stack");
    }

    private static boolean isLanguageSection(String section) {
        return section.equals("languages") || section.equals("language skills");
    }

    private static boolean isStructuredSection(String section) {
        return isEducationSection(section) || isExperienceSection(section) || isProjectSection(section);
    }

    private static boolean isEducationSection(String section) {
        return section.equals("education");
    }

    private static boolean isExperienceSection(String section) {
        return section.equals("experience") || section.equals("work experience");
    }

    private static boolean isProjectSection(String section) {
        return section.equals("projects");
    }

    private static List<ExperienceWriteRequest> experiences(List<List<String>> entries) {
        List<ExperienceWriteRequest> experiences = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            List<String> entry = entries.get(index);
            if (entry.isEmpty()) {
                continue;
            }
            DateRange dateRange = dateRange(entry.getFirst());
            String header = removeDateRange(entry.getFirst()).strip();
            List<String> parts = parts(header);
            String title = parts.isEmpty() ? null : parts.getFirst();
            String company = parts.size() > 1 ? parts.get(1) : null;
            String location = parts.size() > 2 ? parts.get(2) : null;
            String description = description(entry.subList(1, entry.size()));
            experiences.add(new ExperienceWriteRequest(null, company, title, location,
                    dateRange.startDate(), dateRange.endDate(), description, index));
        }
        return experiences;
    }

    private static List<EducationWriteRequest> education(List<List<String>> entries) {
        List<EducationWriteRequest> education = new ArrayList<>();
        for (List<String> entry : entries) {
            if (entry.isEmpty()) {
                continue;
            }
            DateRange dateRange = dateRange(entry.getFirst());
            String header = removeDateRange(entry.getFirst()).strip();
            List<String> parts = parts(header);
            String institution = parts.isEmpty() ? null : parts.getFirst();
            DegreeField degreeField = degreeField(parts.size() > 1 ? parts.get(1) : null);
            String location = parts.size() > 2 ? parts.get(2) : null;
            String focus = description(entry.subList(1, entry.size()));
            education.add(new EducationWriteRequest(null, institution, degreeField.degree(), degreeField.field(),
                    location, dateRange.startDate(), dateRange.endDate(), focus));
        }
        return education;
    }

    private static List<ProjectWriteRequest> projects(List<List<String>> entries) {
        List<ProjectWriteRequest> projects = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            List<String> entry = entries.get(index);
            if (entry.isEmpty()) {
                continue;
            }
            List<String> parts = parts(entry.getFirst());
            String name = parts.isEmpty() ? null : parts.getFirst();
            String url = parts.stream().filter(part -> URL.matcher(part).find()).findFirst()
                    .map(DeterministicProfileTextExtractor::normalizeUrl)
                    .orElse(null);
            String searchableText = String.join("\n", entry);
            List<ProjectTechnologyWriteRequest> technologies = technologies(searchableText);
            String description = description(entry.subList(1, entry.size()));
            projects.add(new ProjectWriteRequest(null, name, url, description, index, technologies));
        }
        return projects;
    }

    private static List<ProjectTechnologyWriteRequest> technologies(String text) {
        return skills(text).stream()
                .map(skill -> new ProjectTechnologyWriteRequest(null, skill.skill(), skill.normalizedSkill(), skill.displayOrder()))
                .toList();
    }

    private static List<String> parts(String header) {
        String normalized = header.replaceAll("\\s+[–—]\\s+", " | ");
        return Arrays.stream(normalized.split("\\s*\\|\\s*"))
                .map(String::strip)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private static String description(List<String> lines) {
        String value = lines.stream()
                .map(line -> line.replaceFirst("^[•*-]\\s*", "").strip())
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
        return value == null ? null : truncate(value);
    }

    private static DateRange dateRange(String line) {
        Matcher matcher = DATE_RANGE.matcher(line);
        if (!matcher.find()) {
            return new DateRange(null, null);
        }
        return new DateRange(parseDate(matcher.group("start")), parseEndDate(matcher.group("end")));
    }

    private static String removeDateRange(String line) {
        return DATE_RANGE.matcher(line).replaceFirst("").replaceAll("\\s*[|,;-]+\\s*$", "");
    }

    private static LocalDate parseEndDate(String rawDate) {
        String normalized = rawDate.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("present") || normalized.equals("current")) {
            return null;
        }
        return parseDate(rawDate);
    }

    private static LocalDate parseDate(String rawDate) {
        String normalized = rawDate.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String[] tokens = normalized.split(" ");
        int year = Integer.parseInt(tokens[tokens.length - 1]);
        Month month = tokens.length == 1 ? Month.JANUARY : month(tokens[0]);
        return LocalDate.of(year, month, 1);
    }

    private static Month month(String token) {
        String prefix = token.substring(0, Math.min(3, token.length()));
        return switch (prefix) {
            case "jan" -> Month.JANUARY;
            case "feb" -> Month.FEBRUARY;
            case "mar" -> Month.MARCH;
            case "apr" -> Month.APRIL;
            case "may" -> Month.MAY;
            case "jun" -> Month.JUNE;
            case "jul" -> Month.JULY;
            case "aug" -> Month.AUGUST;
            case "sep" -> Month.SEPTEMBER;
            case "oct" -> Month.OCTOBER;
            case "nov" -> Month.NOVEMBER;
            case "dec" -> Month.DECEMBER;
            default -> Month.JANUARY;
        };
    }

    private static DegreeField degreeField(String value) {
        if (value == null || value.isBlank()) {
            return new DegreeField(null, null);
        }
        String cleaned = value.strip();
        Matcher matcher = Pattern.compile("^(?<degree>B\\.?A\\.?|B\\.?S\\.?|BSc|BA|M\\.?S\\.?|MSc|MA|PhD|Bachelor(?:'s)?|Master(?:'s)?)\\s+(?:in\\s+)?(?<field>.+)$",
                Pattern.CASE_INSENSITIVE).matcher(cleaned);
        if (matcher.find()) {
            return new DegreeField(matcher.group("degree"), matcher.group("field"));
        }
        return new DegreeField(cleaned, null);
    }

    private static String summary(String text, String summarySectionText) {
        if (summarySectionText != null && !summarySectionText.isBlank()) {
            return truncate(summarySectionText.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .findFirst()
                    .orElse(null));
        }
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> !EMAIL.matcher(line).find())
                .filter(line -> !PHONE.matcher(line).find())
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains("linkedin"))
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains("github"))
                .skip(1)
                .findFirst()
                .map(DeterministicProfileTextExtractor::truncate)
                .orElse(null);
    }

    private static String truncate(String line) {
        if (line == null) {
            return null;
        }
        return line.length() > 500 ? line.substring(0, 500) : line;
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String normalizeUrl(String rawUrl) {
        String url = rawUrl.strip().replaceAll("[.,;]+$", "").replaceAll("[?#].*$", "").replaceAll("/+$", "");
        if (!url.toLowerCase(Locale.ROOT).startsWith("http://") && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private static boolean containsPhrase(String lowerText, String phrase) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(phrase) + "(?![a-z0-9])", Pattern.CASE_INSENSITIVE)
                .matcher(lowerText)
                .find();
    }

    private record ResumeSections(
            String summaryText,
            String skillsText,
            String languagesText,
            List<List<String>> educationEntries,
            List<List<String>> experienceEntries,
            List<List<String>> projectEntries
    ) {
        private ResumeSections {
            educationEntries = immutableEntries(educationEntries);
            experienceEntries = immutableEntries(experienceEntries);
            projectEntries = immutableEntries(projectEntries);
        }
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {
    }

    private record DegreeField(String degree, String field) {
    }

    private static List<List<String>> immutableEntries(List<List<String>> entries) {
        return entries.stream()
                .filter(Objects::nonNull)
                .map(List::copyOf)
                .toList();
    }

    private static String displayName(String normalized) {
        return switch (normalized) {
            case "java" -> "Java";
            case "spring boot" -> "Spring Boot";
            case "spring cloud" -> "Spring Cloud";
            case "spring ai" -> "Spring AI";
            case "kotlin" -> "Kotlin";
            case "kotlin multiplatform" -> "Kotlin Multiplatform";
            case "python" -> "Python";
            case "postgresql" -> "PostgreSQL";
            case "flyway" -> "Flyway";
            case "jdbc" -> "JDBC";
            case "mcp" -> "MCP";
            case "docker" -> "Docker";
            case "testcontainers" -> "Testcontainers";
            case "react" -> "React";
            case "next.js" -> "Next.js";
            case "typescript" -> "TypeScript";
            case "javascript" -> "JavaScript";
            case "qt" -> "Qt";
            case "qml" -> "QML";
            case "pyside6" -> "PySide6";
            case "sqlite" -> "SQLite";
            case "aws" -> "AWS";
            default -> titleCase(normalized);
        };
    }

    private static String titleCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (String part : value.split("\\s+")) {
            if (!part.isBlank()) {
                builder.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.length() > 1 ? part.substring(1).toLowerCase(Locale.ROOT) : "")
                        .append(' ');
            }
        }
        return builder.toString().strip();
    }

    private static ApplicationException invalid(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid profile extraction input",
                Map.of("field", field, "reason", reason),
                null
        );
    }
}
