package org.instruct.jobenginespring.adapter.out.extraction;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ContactWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.SkillWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Set<String> KNOWN_SKILLS = Set.of(
            "java", "spring boot", "spring cloud", "spring ai", "kotlin", "kotlin multiplatform",
            "python", "postgresql", "flyway", "jdbc", "mcp", "docker", "testcontainers",
            "react", "next.js", "typescript", "javascript", "qt", "qml", "pyside6", "sqlite", "aws"
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

        List<LinkWriteRequest> links = links(text);
        List<SkillWriteRequest> skills = skills(text);
        String summary = summary(text);
        return new ProfileWriteRequest(
                fullName,
                email,
                summary,
                contacts,
                links,
                skills,
                List.of(),
                List.of(),
                List.of(),
                List.of()
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
            if (lower.contains(skill)) {
                skills.add(new SkillWriteRequest(null, displayName(skill), skill, "extracted", displayOrder++));
            }
        }
        return skills;
    }

    private static String summary(String text) {
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> !EMAIL.matcher(line).find())
                .filter(line -> !PHONE.matcher(line).find())
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains("linkedin"))
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains("github"))
                .skip(1)
                .findFirst()
                .map(line -> line.length() > 500 ? line.substring(0, 500) : line)
                .orElse(null);
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String normalizeUrl(String rawUrl) {
        String url = rawUrl.strip().replaceAll("[.,;]+$", "");
        if (!url.toLowerCase(Locale.ROOT).startsWith("http://") && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return "https://" + url;
        }
        return url;
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
