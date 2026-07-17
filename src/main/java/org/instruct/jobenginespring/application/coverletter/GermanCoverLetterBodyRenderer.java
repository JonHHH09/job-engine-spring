package org.instruct.jobenginespring.application.coverletter;

import java.util.Objects;

/** Plain-text projection used only for deterministic content review, never as persisted PDF body. */
public final class GermanCoverLetterBodyRenderer {

    private GermanCoverLetterBodyRenderer() {
    }

    public static String render(GermanCoverLetterContent content) {
        GermanCoverLetterContent safe = Objects.requireNonNull(content, "content must not be null");
        StringBuilder body = new StringBuilder();
        append(body, safe.senderName());
        safe.personalFields().forEach(field -> append(body, field.label() + ": " + field.value()));
        if (safe.recipientCompany() != null) {
            append(body, safe.recipientCompany());
        }
        if (safe.recipientLocation() != null) {
            append(body, safe.recipientLocation());
        }
        append(body, safe.subject());
        append(body, safe.salutation());
        safe.paragraphs().forEach(paragraph -> {
            append(body, paragraph);
            body.append('\n');
        });
        append(body, safe.closing());
        append(body, safe.signature());
        return body.toString().strip();
    }

    private static void append(StringBuilder body, String text) {
        body.append(text).append('\n');
    }
}
