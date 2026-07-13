package org.instruct.jobenginespring.application.resume;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanLebenslaufBodyRendererTests {

    @Test
    void rendersEnglishAndGermanSectionSets() {
        StructuredResumeContent english = new StructuredResumeContent(
                "Joni",
                "en",
                List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                List.of(new StructuredResumeContent.ExperienceEntry("Dev", "Co", "City", LocalDate.of(2023, 1, 1), null, List.of("Built APIs"))),
                List.of(new StructuredResumeContent.EducationEntry("B.A.", "Uni", "BG", LocalDate.of(2019, 1, 1), LocalDate.of(2023, 1, 1), List.of("CS"))),
                List.of(new StructuredResumeContent.SkillGroup("Backend", List.of("Java"))),
                List.of(new StructuredResumeContent.LanguageEntry("English", "fluent")),
                List.of(new StructuredResumeContent.AdditionalEntry("Project", "https://x", List.of("Did work")))
        );
        String en = GermanLebenslaufBodyRenderer.render(english);
        assertTrue(en.contains("PERSONAL DATA"));
        assertTrue(en.contains("PROFESSIONAL EXPERIENCE"));
        assertTrue(en.contains("EDUCATION"));
        assertTrue(en.contains("SKILLS"));
        assertTrue(en.contains("LANGUAGES"));
        assertTrue(en.contains("ADDITIONAL QUALIFICATIONS"));

        StructuredResumeContent german = new OfflineGermanResumeTranslator().toGerman(english);
        String de = GermanLebenslaufBodyRenderer.render(german);
        assertTrue(de.contains("PERSÖNLICHE DATEN") || de.contains("PERS"));
        assertTrue(de.contains("BERUFSERFAHRUNG"));
        assertTrue(de.contains("AUSBILDUNG"));
        assertTrue(de.contains("KENNTNISSE"));
        assertTrue(de.contains("SPRACHEN"));
        assertTrue(de.contains("WEITERE QUALIFIKATIONEN"));
    }

    @Test
    void rendersMinimalContentAndRejectsBlankName() {
        StructuredResumeContent minimal = new StructuredResumeContent(
                "Name", "en", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        String rendered = GermanLebenslaufBodyRenderer.render(minimal);
        assertTrue(rendered.contains("Name"));
        assertFalse(rendered.contains("PROFESSIONAL EXPERIENCE"));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent(
                "  ", "en", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.PersonalField(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.PersonalField("Email", " "));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.SkillGroup(" ", List.of("Java")));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.SkillGroup("Backend", List.of()));
        StructuredResumeContent withEmptyBullets = new StructuredResumeContent(
                "Name", "en", List.of(), List.of(new StructuredResumeContent.ExperienceEntry("R", "C", null, null, null, List.of())),
                List.of(), List.of(), List.of(), List.of()
        );
        assertTrue(withEmptyBullets.experiences().getFirst().bullets().isEmpty());
        assertFalse(GermanLebenslaufBodyRenderer.hasText(null));
        assertFalse(GermanLebenslaufBodyRenderer.hasText(" "));
        assertTrue(GermanLebenslaufBodyRenderer.hasText("ok"));
    }
}
