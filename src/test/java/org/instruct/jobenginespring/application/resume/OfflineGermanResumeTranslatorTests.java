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
    void preservesOpaqueContactValuesAndProperNames() {
        StructuredResumeContent english = new StructuredResumeContent(
                "Joni Hysaj",
                "en",
                List.of(
                        new StructuredResumeContent.PersonalField("Email", "root@example.test"),
                        new StructuredResumeContent.PersonalField("Address", "Montreal, QC, Canada"),
                        new StructuredResumeContent.PersonalField("LinkedIn", "https://linkedin.com/in/joni-hysaj-66a56b285")
                ),
                List.of(new StructuredResumeContent.ExperienceEntry(
                        "Java Application Developer",
                        "National Bank of Commerce (BKT)",
                        "Tirana, Albania",
                        null,
                        null,
                        List.of("Worked on systems handling data for more than 100,000 customers.")
                )),
                List.of(new StructuredResumeContent.EducationEntry(
                        "B.A. Computer Science",
                        "American University in Bulgaria",
                        "Blagoevgrad, Bulgaria",
                        null,
                        null,
                        List.of()
                )),
                List.of(),
                List.of(),
                List.of()
        );

        StructuredResumeContent german = translator.toGerman(english);

        assertEquals("root@example.test", german.personalFields().get(0).value());
        assertEquals("Montreal, QC, Kanada", german.personalFields().get(1).value());
        assertEquals("https://linkedin.com/in/joni-hysaj-66a56b285", german.personalFields().get(2).value());
        assertEquals("National Bank of Commerce (BKT)", german.experiences().getFirst().company());
        assertEquals("American University in Bulgaria", german.education().getFirst().institution());
        assertEquals("unchanged", translator.translatePersonalFieldValue(null, "unchanged"));
        assertEquals("unchanged", translator.translatePersonalFieldValue("   ", "unchanged"));
        assertEquals("Kanada", translator.translatePersonalFieldValue("Nationality", "Canada"));
        assertEquals("Tirana, Albanien", translator.translatePersonalFieldValue("Location", "Tirana, Albania"));
    }

    @Test
    void translatesKnownExperienceBulletsIntoCompleteProfessionalGerman() {
        assertEquals(
                "Migrierte die Unternehmenswebsite von WordPress auf einen wartbaren individuellen Technologie-Stack und verantwortete die Umsetzung bis zur Produktivsetzung.",
                translator.translateText("Migrated the company website from WordPress to a maintainable custom stack and took ownership of delivery through deployment.")
        );
        assertEquals(
                "Integrierte Visa-Direct-APIs und arbeitete bei der Umsetzung mit technischen und fachlichen Stakeholdern zusammen.",
                translator.translateText("Integrated Visa Direct APIs and collaborated with technical and business stakeholders on delivery.")
        );
        assertEquals(
                "Arbeitete an Systemen zur Verarbeitung von Daten für mehr als 100.000 Kund:innen.",
                translator.translateText("Worked on systems handling data for more than 100,000 customers.")
        );
        assertEquals(
                "Entwickelte eine Desktop-Geschäftsanwendung mit Electron und Spring Boot sowie mobile Unterstützung mit SwiftUI.",
                translator.translateText("Developed a desktop business application with Electron and Spring Boot plus SwiftUI-based mobile support.")
        );
        assertEquals(
                "Entwickelte wiederverwendbare Frontend-Komponenten und trug zur responsiven Bereitstellung von Websites auf verschiedenen Endgeräten bei.",
                translator.translateText("Developed reusable frontend components and contributed to responsive website delivery across devices.")
        );
        assertEquals(
                "Unterstützte Endanwender:innen bei AutoCAD und Trimble-GPS-Software für Kartierungsarbeiten.",
                translator.translateText("Provided end-user support for AutoCAD and Trimble GPS software used in mapping-related work.")
        );
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
