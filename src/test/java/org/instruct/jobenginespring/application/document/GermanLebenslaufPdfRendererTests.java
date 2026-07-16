package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent.AdditionalEntry;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent.EducationEntry;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent.ExperienceEntry;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent.LanguageEntry;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent.PersonalField;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent.SkillGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanLebenslaufPdfRendererTests {

    @TempDir
    Path tempDir;

    @Test
    void rendersA4ResumeWithSingleNameAndProjectsBeforeEducation() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);

        var result = renderer.generate("germany_candidate_en.pdf", content());

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            assertTrue(document.getNumberOfPages() <= 2);
            PDRectangle page = document.getPage(0).getMediaBox();
            assertEquals(PDRectangle.A4.getWidth(), page.getWidth(), 0.01);
            assertEquals(PDRectangle.A4.getHeight(), page.getHeight(), 0.01);

            String text = new PDFTextStripper().getText(document);
            assertEquals(1, countOccurrences(text, "Alex Example"));
            assertFalse(text.contains("Page 1 of"));
            assertTrue(text.indexOf("PROFILE") < text.indexOf("PROFESSIONAL EXPERIENCE"));
            assertTrue(text.contains("Platform engineer focused on reliable model delivery."));
            assertTrue(text.indexOf("PROJECTS") < text.indexOf("EDUCATION"));
            assertTrue(text.contains("https://example.test/ml-platform"));
            assertFalse(text.contains("https: //"));
            assertTrue(text.contains("Email: alex@example.test"));
            assertEquals(5, countOccurrences(text, "•"));
        }
    }

    @Test
    void rendersGermanFooterLocaleWithoutGenericReportChrome() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        StructuredResumeContent english = content();
        StructuredResumeContent german = new StructuredResumeContent(
                english.fullName(), "de", english.summary(), english.personalFields(), english.experiences(), english.education(),
                english.skillGroups(), english.languages(), english.additional()
        );

        var result = renderer.generate("germany_candidate_de.pdf", german);

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("BERUFSERFAHRUNG"));
            assertTrue(text.contains("PROFIL"));
            assertTrue(text.contains("PROJEKTE"));
            assertTrue(text.contains("Seite 1"));
            assertFalse(text.contains("Page 1"));
        }
    }

    @Test
    void handlesOptionalFieldsLongTokensMultipleEntriesAndSafeFileNames() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        String longUrl = "https://example.test/" + "model-artifact-".repeat(7);
        String longContactUrl = "HTTPS://portfolio.example.test/" + "release-evidence-".repeat(6);
        String longHttpUrl = "HTTP://example.test/" + "evidence-".repeat(12);
        StructuredResumeContent sparse = new StructuredResumeContent(
                "Alex 😀 Example", "en",
                List.of(
                        new PersonalField("Email", "alex@example.test"),
                        new PersonalField("Portfolio", longContactUrl),
                        new PersonalField("Location", "Berlin 😀\nOffice\tWest\0"),
                        new PersonalField("Website", longHttpUrl)
                ),
                List.of(
                        new ExperienceEntry("Engineer", "One", null, null, null, List.of()),
                        new ExperienceEntry("Developer", "Two", "Remote", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), List.of("Delivered software."))
                ),
                List.of(
                        new EducationEntry("B.Sc.", "One", null, null, null, List.of()),
                        new EducationEntry("M.Sc.", "Two", "Berlin", LocalDate.of(2021, 1, 1), LocalDate.of(2023, 1, 1), List.of())
                ),
                List.of(),
                List.of(new LanguageEntry("English", null), new LanguageEntry("German", "basic")),
                List.of(
                        new AdditionalEntry("Private project", null, List.of()),
                        new AdditionalEntry("Published project", longUrl, List.of(
                                "Built a reproducible workflow.",
                                "X".repeat(300)
                        ))
                )
        );

        var result = renderer.generate("../Unsafe Resume Name", sparse);

        assertEquals("Unsafe_Resume_Name.pdf", result.fileName());
        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Alex ? Example"));
            assertTrue(text.contains("Location: Berlin ? Office West"));
            assertTrue(text.contains("Unknown"));
            assertFalse(text.contains("Unknown - Present"));
            assertTrue(text.contains("English, German (basic)"));
            assertTrue(text.contains(longUrl));
            assertTrue(text.contains(longContactUrl));
            assertTrue(text.contains(longHttpUrl));
        }
    }

    @Test
    void wrapsLongCandidateNamesWithoutLosingText() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        String longName = "Alexandra ".repeat(24).strip();
        StructuredResumeContent content = new StructuredResumeContent(
                longName, "en", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var result = renderer.generate("long-name.pdf", content);

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            assertEquals(24, countOccurrences(new PDFTextStripper().getText(document), "Alexandra"));
        }
    }

    @Test
    void separatesTheCandidateNameFromTheContactLine() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        StructuredResumeContent header = new StructuredResumeContent(
                "HeaderName", "en", List.of(new PersonalField("ContactLine", "candidate@example.test")),
                List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var result = renderer.generate("header-spacing.pdf", header);

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            assertTrue(Math.abs(firstCharacterY(document, 'H') - firstCharacterY(document, 'C')) >= 15);
            String instructions = new String(document.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(instructions.contains("\nS\n"));
        }
    }

    @Test
    void rendersTheNameDividerAndUniformSectionDividers() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        for (String language : List.of("en", "de")) {
            StructuredResumeContent content = new StructuredResumeContent(
                    "HeaderName", language, "Profile summary.",
                    List.of(new PersonalField("Email", "candidate@example.test")),
                    List.of(new ExperienceEntry("Engineer", "Example", null, null, null, List.of())),
                    List.of(), List.of(), List.of(), List.of()
            );

            var result = renderer.generate("header-section-dividers-" + language + ".pdf", content);

            try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
                String instructions = new String(document.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertEquals(3, countOccurrences(instructions, "\nS\n"));
            }
        }
    }

    @Test
    void normalizesControlCharactersAcrossAllRenderedTextPaths() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        StructuredResumeContent controls = new StructuredResumeContent(
                "Alex\nExample", "en", "Reliable\tplatform engineer.",
                List.of(new PersonalField("Location", "Berlin\rOffice")),
                List.of(new ExperienceEntry("Engineer\tI", "Example\nSystems", "Remote", null, null,
                        List.of("Delivered\rreliable systems."))),
                List.of(),
                List.of(new SkillGroup("Platform\tEngineering", List.of("Observability\n"))),
                List.of(),
                List.of(new AdditionalEntry("Project\rName", "https://example.test/control\tvalue", List.of("Evidence\nrecord.")))
        );

        var result = renderer.generate("controls.pdf", controls);

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Alex Example"));
            assertTrue(text.contains("Location: Berlin Office"));
            assertTrue(text.contains("Reliable platform engineer."));
            assertTrue(text.contains("Delivered reliable systems."));
            assertFalse(text.contains("\u0000"));
        }
    }

    @Test
    void usesDefaultFileNameAndRejectsInvalidInputs() {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);

        assertEquals("resume.pdf", renderer.generate(null, minimalContent()).fileName());
        assertThrows(NullPointerException.class, () -> new GermanLebenslaufPdfRenderer(null));
        assertThrows(NullPointerException.class, () -> renderer.generate("resume.pdf", null));
        assertThrows(ApplicationException.class, () -> renderer.generate("bad\0name", minimalContent()));
    }

    @Test
    void mapsFileSystemFailuresToApplicationErrors() throws IOException {
        Path regularFile = Files.writeString(tempDir.resolve("not-a-directory"), "occupied");
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(regularFile);

        assertThrows(ApplicationException.class, () -> renderer.generate("resume.pdf", minimalContent()));
    }

    @Test
    void rendersAValidBalancedTwoPageResume() throws IOException {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        List<ExperienceEntry> experiences = new java.util.ArrayList<>();
        for (int index = 0; index < 15; index++) {
            experiences.add(new ExperienceEntry(
                    "Platform Engineer " + index, "Example Systems", "Berlin",
                    LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1),
                    List.of(
                            "Built deterministic deployment workflows and observable lifecycle controls for regulated systems.",
                            "Collaborated with technical and business stakeholders to deliver measurable operational outcomes."
                    )
            ));
        }
        StructuredResumeContent base = minimalContent();
        StructuredResumeContent twoPage = new StructuredResumeContent(
                "Alex Example", "en", base.personalFields(), experiences,
                base.education(), base.skillGroups(), base.languages(), List.of()
        );

        var result = renderer.generate("two-page.pdf", twoPage);

        assertEquals(2, result.pageCount());
        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("1 / 2"));
            assertTrue(text.contains("2 / 2"));
            document.getPages().forEach(page -> {
                assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01);
                assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight(), 0.01);
            });
            PDFTextStripper secondPage = new PDFTextStripper();
            secondPage.setStartPage(2);
            secondPage.setEndPage(2);
            assertTrue(secondPage.getText(document).lines().count() >= 15);
        }
    }

    @Test
    void rejectsContentThatCannotFitWithinTwoPagesOrOneBlock() {
        GermanLebenslaufPdfRenderer renderer = new GermanLebenslaufPdfRenderer(tempDir);
        ExperienceEntry oversized = new ExperienceEntry(
                "Engineer", "Example", null, null, null, List.of("word ".repeat(2500))
        );
        StructuredResumeContent oversizedBlock = new StructuredResumeContent(
                "Alex Example", "en", List.of(), List.of(oversized), List.of(), List.of(), List.of(), List.of()
        );
        assertThrows(ApplicationException.class, () -> renderer.generate("oversized.pdf", oversizedBlock));

        List<ExperienceEntry> many = java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> new ExperienceEntry(
                        "Engineer " + index, "Example", null, null, null,
                        List.of("Built reliable systems with deterministic operational controls and observable delivery workflows.")
                ))
                .toList();
        StructuredResumeContent tooManyPages = new StructuredResumeContent(
                "Alex Example", "en", List.of(), many, List.of(), List.of(), List.of(), List.of()
        );
        assertThrows(ApplicationException.class, () -> renderer.generate("too-many.pdf", tooManyPages));

        String unreadableUrl = "https://example.test/" + "model-artifact-".repeat(30);
        StructuredResumeContent unreadableProjectUrl = new StructuredResumeContent(
                "Alex Example", "en", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new AdditionalEntry("Project", unreadableUrl, List.of()))
        );
        assertThrows(ApplicationException.class, () -> renderer.generate("unreadable-url.pdf", unreadableProjectUrl));

        StructuredResumeContent unreadableContactUrl = new StructuredResumeContent(
                "Alex Example", "en", List.of(new PersonalField("Portfolio", unreadableUrl)),
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        assertThrows(ApplicationException.class, () -> renderer.generate("unreadable-contact.pdf", unreadableContactUrl));

        boolean sparseTrailingPageRejected = false;
        for (int wordCount = 1200; wordCount <= 1800; wordCount += 25) {
            ExperienceEntry almostPage = new ExperienceEntry(
                    "Engineer", "Example", null, null, null, List.of("word ".repeat(wordCount))
            );
            StructuredResumeContent sparseTrailingPage = new StructuredResumeContent(
                    "Alex Example", "en", List.of(), List.of(almostPage), List.of(), List.of(),
                    List.of(new LanguageEntry("English", "fluent")), List.of()
            );
            try {
                renderer.generate("sparse-trailing-" + wordCount + ".pdf", sparseTrailingPage);
            } catch (ApplicationException exception) {
                sparseTrailingPageRejected = true;
                break;
            }
        }
        assertTrue(sparseTrailingPageRejected);

        ExperienceEntry almostPage = new ExperienceEntry(
                "Engineer", "Example", null, null, null, List.of("word ".repeat(1400))
        );

        List<SkillGroup> trailingSkills = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> new SkillGroup("Category " + index, List.of("Skill A", "Skill B", "Skill C")))
                .toList();
        StructuredResumeContent overflowingMove = new StructuredResumeContent(
                "Alex Example", "en", List.of(), List.of(almostPage), List.of(), trailingSkills, List.of(), List.of()
        );
        assertThrows(ApplicationException.class, () -> renderer.generate("overflowing-move.pdf", overflowingMove));
    }

    private static StructuredResumeContent content() {
        return new StructuredResumeContent(
                "Alex Example",
                "en",
                "Platform engineer focused on reliable model delivery.",
                List.of(
                        new PersonalField("Email", "alex@example.test"),
                        new PersonalField("Location", "Berlin, Germany"),
                        new PersonalField("GitHub", "https://github.com/example")
                ),
                List.of(new ExperienceEntry(
                        "Platform Engineer", "Example Systems", "Berlin, Germany",
                        LocalDate.of(2023, 1, 1), null,
                        List.of(
                                "Built deterministic deployment workflows for regulated systems.",
                                "Collaborated with technical and business stakeholders on delivery."
                        )
                )),
                List.of(new EducationEntry(
                        "B.Sc. Computer Science", "Example University", "Berlin, Germany",
                        LocalDate.of(2019, 9, 1), LocalDate.of(2022, 6, 1),
                        List.of("Software engineering and distributed systems")
                )),
                List.of(
                        new SkillGroup("Machine Learning / MLOps", List.of("MLOps", "Model Registry", "Drift Detection")),
                        new SkillGroup("Cloud / Ops", List.of("Docker", "CI/CD", "Observability"))
                ),
                List.of(new LanguageEntry("English", "fluent")),
                List.of(new AdditionalEntry(
                        "CART MLOps Platform", "https://example.test/ml-platform",
                        List.of(
                                "Developing a deterministic model lifecycle platform.",
                                "Technologies: Python, MLOps, Docker"
                        )
                ))
        );
    }

    private static StructuredResumeContent minimalContent() {
        return new StructuredResumeContent(
                "Alex Example", "en",
                List.of(new PersonalField("Email", "alex@example.test")),
                List.of(),
                List.of(new EducationEntry("B.Sc.", "Example University", null, null, null, List.of())),
                List.of(new SkillGroup("Backend", List.of("Java"))),
                List.of(new LanguageEntry("English", "fluent")),
                List.of()
        );
    }

    private static int countOccurrences(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    private static float firstCharacterY(PDDocument document, char expected) throws IOException {
        List<Float> positions = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                if (text.getUnicode().length() == 1 && text.getUnicode().charAt(0) == expected) {
                    positions.add(text.getYDirAdj());
                }
            }
        };
        stripper.getText(document);
        assertFalse(positions.isEmpty(), () -> "Expected character not rendered: " + expected);
        return positions.getFirst();
    }
}
