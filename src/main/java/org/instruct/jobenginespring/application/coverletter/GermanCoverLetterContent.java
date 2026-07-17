package org.instruct.jobenginespring.application.coverletter;

import java.util.List;
import java.util.Objects;

/** Structured German cover-letter content kept independent from the generated PDF. */
public record GermanCoverLetterContent(
        String senderName,
        List<PersonalField> personalFields,
        String recipientCompany,
        String recipientLocation,
        String jobTitle,
        String subject,
        String salutation,
        List<String> paragraphs,
        String closing,
        String signature
) {
    public GermanCoverLetterContent {
        senderName = required(senderName, "senderName");
        personalFields = personalFields == null ? List.of() : List.copyOf(personalFields);
        recipientCompany = optional(recipientCompany);
        recipientLocation = optional(recipientLocation);
        jobTitle = required(jobTitle, "jobTitle");
        subject = required(subject, "subject");
        salutation = required(salutation, "salutation");
        paragraphs = normalizeParagraphs(paragraphs);
        closing = required(closing, "closing");
        signature = required(signature, "signature");
    }

    public record PersonalField(String label, String value) {
        public PersonalField {
            label = required(label, "label");
            value = required(value, "value");
        }
    }

    private static List<String> normalizeParagraphs(List<String> values) {
        Objects.requireNonNull(values, "paragraphs must not be null");
        return values.stream().map(value -> required(value, "paragraph")).toList();
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stripped;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
