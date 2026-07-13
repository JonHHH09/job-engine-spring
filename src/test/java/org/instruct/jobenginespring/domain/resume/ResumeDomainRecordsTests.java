package org.instruct.jobenginespring.domain.resume;

import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void rejectsUnsupportedAndBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> new Resume(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "canada", NOW, NOW, NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new Resume(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "  ", NOW, NOW, NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeVariant(
                UUID.randomUUID(), UUID.randomUUID(), "fr", UUID.randomUUID(), "x.pdf", NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeVariant(
                UUID.randomUUID(), UUID.randomUUID(), "en", UUID.randomUUID(), "  ", NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeSection(
                UUID.randomUUID(), UUID.randomUUID(), "  ", "Title", 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeSection(
                UUID.randomUUID(), UUID.randomUUID(), "experience", "  ", 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeSection(
                UUID.randomUUID(), UUID.randomUUID(), "experience", "Title", -1
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeEntry(
                UUID.randomUUID(), UUID.randomUUID(), "  ", 0, null, null, null, null, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeEntry(
                UUID.randomUUID(), UUID.randomUUID(), "experience", -1, null, null, null, null, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeEntryBullet(
                UUID.randomUUID(), UUID.randomUUID(), 0, "  "
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResumeEntryBullet(
                UUID.randomUUID(), UUID.randomUUID(), -1, "ok"
        ));
    }

    @Test
    void personalDetailsNormalizeBlankNationalityAndPhotoFlag() {
        ProfilePersonalDetails withBlank = new ProfilePersonalDetails(
                UUID.randomUUID(), LocalDate.of(2000, 1, 1), "  ", null, NOW, NOW
        );
        assertNull(withBlank.nationality());
        assertFalse(withBlank.hasPhoto());
        ProfilePersonalDetails withPhoto = new ProfilePersonalDetails(
                UUID.randomUUID(), null, "Canadian", UUID.randomUUID(), NOW, NOW
        );
        assertTrue(withPhoto.hasPhoto());
        assertThrows(IllegalArgumentException.class, () -> new ProfilePersonalDetails(
                UUID.randomUUID(), null, null, null, NOW, NOW.minusSeconds(1)
        ));
    }

    @Test
    void entryBlankToNull() {
        ResumeEntry entry = new ResumeEntry(
                UUID.randomUUID(), UUID.randomUUID(), " experience ", 0, "  ", "  ", "  ", null, null, "  "
        );
        assertNull(entry.title());
        assertNull(entry.organization());
        assertNull(entry.location());
        assertNull(entry.metadata());
        assertEquals("experience", entry.entryType());
        ResumeSection section = new ResumeSection(UUID.randomUUID(), UUID.randomUUID(), " experience ", " Title ", 1);
        assertEquals("experience", section.sectionType());
        assertEquals("Title", section.title());
    }
}
