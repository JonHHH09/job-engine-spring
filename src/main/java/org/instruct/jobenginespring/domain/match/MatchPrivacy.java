package org.instruct.jobenginespring.domain.match;

import java.util.Locale;
import java.util.regex.Pattern;

final class MatchPrivacy {
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,79}");
    private static final Pattern NORMALIZED_SKILL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 .+#/_-]{0,79}");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(@|://|\\blocalhost\\b|\\b127\\.|\\b10\\.|\\b192\\.168\\.|\\b172\\.(1[6-9]|2[0-9]|3[01])\\.|" +
                    "\\b(?:[a-z0-9-]+\\.)+(com|net|org|io|local|internal|test)\\b|\\+?\\d[\\d ()-]{7,}\\d|" +
                    "\\b(prompt|chain[ _-]?of[ _-]?thought|reasoning trace|resume|curriculum vitae|contact|address|phone|" +
                    "password|secret|api[ _-]?key|access[ _-]?token|bearer)\\b)");

    private static final Pattern SENSITIVE_SKILL = Pattern.compile(
            "(?i)(@|://|\\blocalhost\\b|\\b(?:127|10)\\.|\\b192\\.168\\.|\\b172\\.(1[6-9]|2[0-9]|3[01])\\.|" +
                    "\\b(prompt|chain[ _-]?of[ _-]?thought|resume|contact|password|secret|api[ _-]?key|access[ _-]?token|bearer)\\b)");

    private MatchPrivacy() {}

    static String label(String value, String field) {
        if (value == null || !LABEL.matcher(value.trim()).matches() || sensitive(value)) {
            throw new IllegalArgumentException(field + " must be a normalized safe label");
        }
        return value.trim();
    }

    static String evidenceFact(String value, String sourceType) {
        if (!"normalized_skill".equals(sourceType)) return text(value, "fact", 500);
        var normalized = value == null ? "" : value.trim();
        if (!NORMALIZED_SKILL.matcher(normalized).matches() || SENSITIVE_SKILL.matcher(normalized).find()) {
            throw new IllegalArgumentException("fact must be a normalized privacy-safe skill");
        }
        return normalized;
    }

    static String text(String value, String field, int maximumLength) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || normalized.contains("\n") || normalized.contains("\r") || sensitive(normalized)) {
            throw new IllegalArgumentException(field + " must be normalized privacy-safe text");
        }
        return normalized;
    }

    private static boolean sensitive(String value) {
        return SENSITIVE.matcher(value.toLowerCase(Locale.ROOT)).find();
    }
}
