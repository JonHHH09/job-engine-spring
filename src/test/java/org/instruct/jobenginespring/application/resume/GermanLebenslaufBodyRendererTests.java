package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanLebenslaufBodyRendererTests {

    @Test
    void rendersWithoutPersonalDataHeaderAndSupportsProjects() {
        StructuredResumeContent english = validContent("en", true);
        String body = GermanLebenslaufBodyRenderer.render(english);
        assertTrue(body.startsWith("Joni"));
        assertTrue(body.contains("Email: a@b.c"));
        assertFalse(body.toUpperCase().contains("PERSONAL DATA"));
        assertTrue(body.contains("PROFESSIONAL EXPERIENCE"));
        assertTrue(body.contains("PROJECTS"));
        assertTrue(body.contains("01/2023 - Present") || body.contains("01/2023 - "));
    }

    @Test
    void germanVariantUsesGermanSectionTitlesWithoutPersonalDataHeader() {
        String body = GermanLebenslaufBodyRenderer.render(validContent("de", false));
        assertTrue(body.contains("BERUFSERFAHRUNG"));
        assertTrue(body.contains("AUSBILDUNG"));
        assertFalse(body.toUpperCase().contains("PERS"));
        assertFalse(body.contains("PROJECTS"));
        assertFalse(body.contains("PROJEKTE"));
    }

    @Test
    void contentReviewAcceptsValidAndRejectsMissingEmail() {
        assertDoesNotThrow(() -> GermanLebenslaufContentReview.review(validContent("en", false)));
        StructuredResumeContent missingEmail = new StructuredResumeContent(
                "Name",
                "en",
                List.of(new StructuredResumeContent.PersonalField("Phone", "123")),
                List.of(new StructuredResumeContent.ExperienceEntry(
                        "Dev", "Co", "City", LocalDate.of(2023, 1, 1), null, List.of("Built APIs"))),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        assertThrows(ApplicationException.class, () -> GermanLebenslaufContentReview.review(missingEmail));
    }

    @Test
    void hasTextCoversNullBlankAndPresent() {
        assertFalse(GermanLebenslaufBodyRenderer.hasText(null));
        assertFalse(GermanLebenslaufBodyRenderer.hasText(" "));
        assertFalse(GermanLebenslaufBodyRenderer.hasText(""));
        assertTrue(GermanLebenslaufBodyRenderer.hasText("ok"));
    }

    private static StructuredResumeContent validContent(String language, boolean withProjects) {
        return new StructuredResumeContent(
                "Joni Hysaj",
                language,
                List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                List.of(new StructuredResumeContent.ExperienceEntry(
                        "Dev", "Co", "City", LocalDate.of(2023, 1, 1), null, List.of("Built APIs"))),
                List.of(new StructuredResumeContent.EducationEntry(
                        "B.A.", "Uni", "BG", LocalDate.of(2019, 9, 1), LocalDate.of(2023, 5, 1), List.of("CS"))),
                List.of(new StructuredResumeContent.SkillGroup("Backend", List.of("Java"))),
                List.of(new StructuredResumeContent.LanguageEntry("English", "fluent")),
                withProjects
                        ? List.of(new StructuredResumeContent.AdditionalEntry("Portfolio", "https://example.test", List.of("Next.js")))
                        : List.of()
        );
    }
}
