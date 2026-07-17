package org.instruct.jobenginespring.domain.coverletter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoverLetterDomainRecordsTests {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void normalizesTheInitialGermanVariantAndKeepsStructuredFields() {
        UUID coverLetterId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        CoverLetter parent = new CoverLetter(
                coverLetterId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                NOW,
                NOW,
                NOW,
                NOW,
                NOW
        );
        CoverLetterVariant variant = new CoverLetterVariant(
                variantId,
                coverLetterId,
                " Germany ",
                " DE ",
                UUID.randomUUID(),
                " tmp/cover-letter.pdf ",
                " Bewerbung als Entwickler",
                " Sehr geehrte Damen und Herren, ",
                " Mit freundlichen Grüßen, ",
                " Synthetic Candidate ",
                NOW,
                NOW
        );
        CoverLetterParagraph paragraph = new CoverLetterParagraph(
                UUID.randomUUID(), variantId, 0, " Evidence-grounded paragraph "
        );

        assertEquals("germany", variant.format());
        assertEquals("de", variant.language());
        assertEquals("tmp/cover-letter.pdf", variant.filePath());
        assertEquals("Evidence-grounded paragraph", paragraph.text());
        assertEquals(coverLetterId, parent.id());
    }

    @Test
    void rejectsUnsupportedValuesAndInvalidOrdering() {
        assertThrows(IllegalArgumentException.class, () -> new CoverLetterVariant(
                UUID.randomUUID(), UUID.randomUUID(), "canada", "de", UUID.randomUUID(),
                "letter.pdf", "Subject", "Salutation", "Closing", "Signature", NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new CoverLetterVariant(
                UUID.randomUUID(), UUID.randomUUID(), "germany", "en", UUID.randomUUID(),
                "letter.pdf", "Subject", "Salutation", "Closing", "Signature", NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new CoverLetterParagraph(
                UUID.randomUUID(), UUID.randomUUID(), -1, "paragraph"
        ));
        assertThrows(IllegalArgumentException.class, () -> new CoverLetterParagraph(
                UUID.randomUUID(), UUID.randomUUID(), 0, "   "
        ));
        assertThrows(IllegalArgumentException.class, () -> new CoverLetter(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                NOW, NOW, NOW, NOW, NOW.minusSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new CoverLetterVariant(
                UUID.randomUUID(), UUID.randomUUID(), "germany", "de", UUID.randomUUID(),
                "letter.pdf", "Subject", "Salutation", "Closing", "Signature", NOW, NOW.minusSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new CoverLetterVariant(
                UUID.randomUUID(), UUID.randomUUID(), "germany", "de", UUID.randomUUID(),
                " ", "Subject", "Salutation", "Closing", "Signature", NOW, NOW
        ));
    }
}
