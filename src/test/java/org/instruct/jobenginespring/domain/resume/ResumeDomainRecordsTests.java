package org.instruct.jobenginespring.domain.resume;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResumeDomainRecordsTests {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    @Test
    void normalizesGermanyFormatAndLanguage() {
        Resume resume = new Resume(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), " Germany ", NOW, NOW, NOW, NOW);
        assertEquals("germany", resume.format());

        ResumeVariant variant = new ResumeVariant(
                UUID.randomUUID(), resume.id(), " EN ", UUID.randomUUID(), " /tmp/a.pdf ", NOW, NOW
        );
        assertEquals("en", variant.language());
        assertEquals("/tmp/a.pdf", variant.filePath());
    }

    @Test
    void rejectsUnsupportedFormatAndLanguage() {
        assertThrows(IllegalArgumentException.class, () -> new Resume(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "canada", NOW, NOW, NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeVariant(
                UUID.randomUUID(), UUID.randomUUID(), "fr", UUID.randomUUID(), "x.pdf", NOW, NOW
        ));
    }

    @Test
    void rejectsBlankBulletText() {
        assertThrows(IllegalArgumentException.class, () -> new ResumeEntryBullet(
                UUID.randomUUID(), UUID.randomUUID(), 0, "  "
        ));
    }
}
