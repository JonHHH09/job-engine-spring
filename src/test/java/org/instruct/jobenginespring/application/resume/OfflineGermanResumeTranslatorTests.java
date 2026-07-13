package org.instruct.jobenginespring.application.resume;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineGermanResumeTranslatorTests {

    private final OfflineGermanResumeTranslator translator = new OfflineGermanResumeTranslator();

    @Test
    void translatesKnownLabelsAndPhrasesOffline() {
        assertEquals("E-Mail", translator.translateLabel("Email"));
        assertEquals("Geburtsdatum", translator.translateLabel("Date of birth"));
        assertTrue(translator.translateText("Java Application Developer").toLowerCase().contains("java"));
        assertTrue(translator.translateText("Automated financial processes using Spring Boot and Hibernate in a regulated banking environment")
                .toLowerCase().contains("spring"));
    }

    @Test
    void coversBlankLabelsUnknownLabelsAndCasePreservation() {
        assertNull(translator.translateText(null));
        assertEquals("   ", translator.translateText("   "));
        assertNull(translator.translateLabel(null));
        assertEquals("   ", translator.translateLabel("   "));
        assertEquals("Unmapped Label", translator.translateLabel("Unmapped Label"));
        assertEquals("ERSTELLTE", translator.translateText("BUILT"));
        assertEquals("Erstellte", translator.translateText("Built"));
        assertEquals("zz", translator.translateText("zz"));
        assertEquals("ERSTELLTE", OfflineGermanResumeTranslator.preserveCaseForTest("BUILT", "erstellte"));
        assertEquals("Erstellte", OfflineGermanResumeTranslator.preserveCaseForTest("Built", "erstellte"));
        assertEquals("erstellte", OfflineGermanResumeTranslator.preserveCaseForTest("built", "erstellte"));
        assertEquals("erstellte", OfflineGermanResumeTranslator.preserveCaseForTest("", "erstellte"));
        assertEquals("E", OfflineGermanResumeTranslator.preserveCaseForTest("B", "erstellte").substring(0, 1));
    }

    @Test
    void structuredContentNullListNormalizationAndRendererEdgeCases() {
        StructuredResumeContent content = new StructuredResumeContent(
                "Name", "en", null, null,
                List.of(new StructuredResumeContent.EducationEntry("Degree", "Uni", null, null, null, List.of("focus"))),
                null,
                List.of(new StructuredResumeContent.LanguageEntry("English", null)),
                null
        );
        String rendered = GermanLebenslaufBodyRenderer.render(content);
        assertTrue(rendered.contains("EDUCATION"));
        assertTrue(rendered.contains("LANGUAGES"));

        StructuredResumeContent withNullBullet = new StructuredResumeContent(
                "Name", "de", List.of(),
                List.of(new StructuredResumeContent.ExperienceEntry(
                        "Role", "Co", "  ", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), listWithNull("Did work"))),
                List.of(), List.of(), List.of(),
                List.of(new StructuredResumeContent.AdditionalEntry("Proj", null, List.of("x")))
        );
        assertEquals(1, withNullBullet.experiences().getFirst().bullets().size());
        assertNull(withNullBullet.experiences().getFirst().location());
        assertTrue(GermanLebenslaufBodyRenderer.render(withNullBullet).contains("BERUFSERFAHRUNG"));

        StructuredResumeContent withNullBullets = new StructuredResumeContent(
                "Name", "en", List.of(),
                List.of(new StructuredResumeContent.ExperienceEntry("Role", "Co", null, null, null, null)),
                List.of(new StructuredResumeContent.EducationEntry("Deg", "Uni", null, null, null, null)),
                List.of(),
                List.of(new StructuredResumeContent.LanguageEntry("English", "  ")),
                List.of(new StructuredResumeContent.AdditionalEntry("A", "  ", null))
        );
        assertTrue(withNullBullets.experiences().getFirst().bullets().isEmpty());
        assertNull(withNullBullets.languages().getFirst().proficiency());
        assertNull(withNullBullets.additional().getFirst().organization());
    }

    private static List<String> listWithNull(String value) {
        List<String> bullets = new ArrayList<>();
        bullets.add(null);
        bullets.add("  ");
        bullets.add(value);
        return bullets;
    }
}
