package org.instruct.jobenginespring.application.coverletter;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fail-closed review of deterministic structured German cover-letter content. */
public final class GermanCoverLetterContentReview {

    private static final int MIN_RENDERED_CHARACTERS = 140;

    private GermanCoverLetterContentReview() {
    }

    public static void review(GermanCoverLetterContent content) {
        GermanCoverLetterContent safe = Objects.requireNonNull(content, "content must not be null");
        List<String> defects = new ArrayList<>();
        if (safe.personalFields().stream().noneMatch(GermanCoverLetterContentReview::isEmail)) {
            defects.add("contact email is required");
        }
        if (safe.paragraphs().size() < 2) {
            defects.add("at least two ordered paragraphs are required");
        }
        if (safe.subject().length() < 12) {
            defects.add("subject is too short");
        }
        if (!safe.subject().toLowerCase(Locale.ROOT).contains(safe.jobTitle().toLowerCase(Locale.ROOT))) {
            defects.add("subject must identify the job title");
        }
        if (containsControlCharacters(safe)) {
            defects.add("content must not contain control characters");
        }
        if (GermanCoverLetterBodyRenderer.render(safe).length() < MIN_RENDERED_CHARACTERS) {
            defects.add("rendered body is too short to be a usable cover letter");
        }
        if (!defects.isEmpty()) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "German cover-letter content review failed",
                    Map.of("defects", String.join("; ", defects)),
                    null
            );
        }
    }

    private static boolean isEmail(GermanCoverLetterContent.PersonalField field) {
        return field.value().contains("@") || field.label().toLowerCase(Locale.ROOT).contains("mail");
    }

    private static boolean containsControlCharacters(GermanCoverLetterContent content) {
        List<String> values = new ArrayList<>();
        values.add(content.senderName());
        values.add(content.recipientCompany());
        values.add(content.recipientLocation());
        values.add(content.jobTitle());
        values.add(content.subject());
        values.add(content.salutation());
        values.addAll(content.paragraphs());
        values.add(content.closing());
        values.add(content.signature());
        content.personalFields().forEach(field -> {
            values.add(field.label());
            values.add(field.value());
        });
        return values.stream().filter(Objects::nonNull).anyMatch(value ->
                value.codePoints().anyMatch(Character::isISOControl));
    }
}
