package org.instruct.jobenginespring.application.coverletter;

import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository;
import org.instruct.jobenginespring.domain.coverletter.CoverLetter;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoverLetterRepositoryContractTests {

    @Test
    void copiesParagraphsAndPreviousVariantsAtPortBoundary() {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        CoverLetter parent = new CoverLetter(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now, now, now, now);
        CoverLetterVariant variant = new CoverLetterVariant(UUID.randomUUID(), parent.id(), "germany", "de", UUID.randomUUID(), "letter.pdf",
                "Subject", "Salutation", "Closing", "Signature", now, now);
        CoverLetterRepository.VariantWrite write = new CoverLetterRepository.VariantWrite(variant, List.of());
        CoverLetterRepository.ReplaceResult result = new CoverLetterRepository.ReplaceResult(parent, variant, List.of());

        assertEquals(List.of(), write.paragraphs());
        assertEquals(List.of(), result.previousVariants());
    }

    @Test
    void rejectsNullAggregateParts() {
        assertThrows(NullPointerException.class, () -> new CoverLetterRepository.CoverLetterAggregateWrite(null, null));
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        CoverLetter parent = new CoverLetter(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                now, now, now, now, now);
        CoverLetterVariant variant = new CoverLetterVariant(UUID.randomUUID(), parent.id(), "germany", "de",
                UUID.randomUUID(), "letter.pdf", "Subject", "Salutation", "Closing", "Signature", now, now);

        assertThrows(NullPointerException.class, () -> new CoverLetterRepository.CoverLetterAggregateWrite(parent, null));
        assertThrows(NullPointerException.class, () -> new CoverLetterRepository.VariantWrite(null, List.of()));
        assertThrows(NullPointerException.class, () -> new CoverLetterRepository.VariantWrite(variant, null));
        assertThrows(NullPointerException.class, () -> new CoverLetterRepository.ReplaceResult(null, variant, List.of()));
        assertThrows(NullPointerException.class, () -> new CoverLetterRepository.ReplaceResult(parent, null, List.of()));
        assertThrows(NullPointerException.class, () -> new CoverLetterRepository.ReplaceResult(parent, variant, null));
    }
}
