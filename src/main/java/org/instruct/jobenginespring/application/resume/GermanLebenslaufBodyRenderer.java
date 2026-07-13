package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.domain.resume.ResumeVariant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Renders structured Lebenslauf content into PDF body text (no summary section). */
public final class GermanLebenslaufBodyRenderer {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private GermanLebenslaufBodyRenderer() {
    }

    public static String render(StructuredResumeContent content) {
        StructuredResumeContent safe = Objects.requireNonNull(content, "content must not be null");
        boolean german = ResumeVariant.LANGUAGE_DE.equals(safe.language());
        StringBuilder body = new StringBuilder();
        appendLine(body, safe.fullName());
        appendBlank(body);

        appendSection(body, german ? "PERSÖNLICHE DATEN" : "PERSONAL DATA");
        safe.personalFields().forEach(field -> appendLine(body, field.label() + ": " + field.value()));
        appendBlank(body);

        if (!safe.experiences().isEmpty()) {
            appendSection(body, german ? "BERUFSERFAHRUNG" : "PROFESSIONAL EXPERIENCE");
            safe.experiences().forEach(experience -> {
                appendLine(body, experience.title() + " | " + experience.company());
                appendLine(body, period(experience.startDate(), experience.endDate(), german)
                        + (hasText(experience.location()) ? " | " + experience.location() : ""));
                experience.bullets().forEach(bullet -> appendLine(body, "- " + bullet));
                appendBlank(body);
            });
        }

        if (!safe.education().isEmpty()) {
            appendSection(body, german ? "AUSBILDUNG" : "EDUCATION");
            safe.education().forEach(item -> {
                appendLine(body, item.degree() + " | " + item.institution());
                appendLine(body, period(item.startDate(), item.endDate(), german)
                        + (hasText(item.location()) ? " | " + item.location() : ""));
                item.bullets().forEach(bullet -> appendLine(body, "- " + bullet));
                appendBlank(body);
            });
        }

        if (!safe.skillGroups().isEmpty()) {
            appendSection(body, german ? "KENNTNISSE" : "SKILLS");
            safe.skillGroups().forEach(group -> appendLine(body,
                    group.category() + ": " + String.join(", ", group.skills())));
            appendBlank(body);
        }

        if (!safe.languages().isEmpty()) {
            appendSection(body, german ? "SPRACHEN" : "LANGUAGES");
            appendLine(body, safe.languages().stream()
                    .map(language -> hasText(language.proficiency())
                            ? language.language() + " (" + language.proficiency() + ")"
                            : language.language())
                    .collect(Collectors.joining(", ")));
            appendBlank(body);
        }

        if (!safe.additional().isEmpty()) {
            appendSection(body, german ? "WEITERE QUALIFIKATIONEN" : "ADDITIONAL QUALIFICATIONS");
            safe.additional().forEach(item -> {
                String header = item.title();
                if (hasText(item.organization())) {
                    header = header + " | " + item.organization();
                }
                appendLine(body, header);
                item.bullets().forEach(bullet -> appendLine(body, "- " + bullet));
                appendBlank(body);
            });
        }

        return body.toString().strip();
    }

    private static String period(LocalDate start, LocalDate end, boolean german) {
        String unknown = german ? "unbekannt" : "Unknown";
        String present = german ? "heute" : "Present";
        if (start == null && end == null) {
            return unknown;
        }
        String startText = start == null ? unknown : MONTH.format(start);
        String endText = end == null ? present : MONTH.format(end);
        return startText + " - " + endText;
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
