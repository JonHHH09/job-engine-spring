package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.instruct.jobenginespring.application.coverletter.GermanCoverLetterContent;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanCoverLetterPdfRendererTests {

    @TempDir
    Path tempDir;

    @Test
    void createsSelectableA4PdfWithGermanResumeVisualFamily() throws Exception {
        GermanCoverLetterPdfRenderer renderer = new GermanCoverLetterPdfRenderer(tempDir);
        var result = renderer.generate("germany_cover_letter_synthetic.pdf", content());

        Path path = Path.of(result.path());
        assertTrue(Files.exists(path));
        assertTrue(Files.readAllBytes(path).length > 5);
        assertEquals('%', Files.readAllBytes(path)[0]);
        assertEquals(1, result.pageCount());
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            assertEquals(595.27563f, document.getPage(0).getMediaBox().getWidth(), 0.01f);
            assertEquals(841.8898f, document.getPage(0).getMediaBox().getHeight(), 0.01f);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Bewerbung als Backend Engineer bei Example GmbH"));
            assertTrue(text.contains("https://example.test/synthetic"));
            assertTrue(text.contains("Mit freundlichen Grüßen"));
        }
    }

    @Test
    void rejectsMoreThanTwoA4PagesInsteadOfClipping() {
        List<String> paragraphs = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            paragraphs.add("Nachweis " + index + ": Java PostgreSQL und zuverlässige Backend-Systeme in einer klar beschriebenen Tätigkeit.");
        }
        GermanCoverLetterContent oversized = new GermanCoverLetterContent(
                "Synthetic Candidate", List.of(new GermanCoverLetterContent.PersonalField("E-Mail", "candidate@example.test")),
                "Example GmbH", "Berlin", "Backend Engineer", "Bewerbung als Backend Engineer bei Example GmbH",
                "Sehr geehrte Damen und Herren,", paragraphs, "Mit freundlichen Grüßen,", "Synthetic Candidate"
        );

        assertThrows(ApplicationException.class, () -> new GermanCoverLetterPdfRenderer(tempDir)
                .generate("oversized.pdf", oversized));
    }

    @Test
    void rendersTwoPagesAndWrapsLongTokensAndAllContactShapes() throws Exception {
        String longToken = "A".repeat(180);
        GermanCoverLetterContent twoPage = new GermanCoverLetterContent(
                "Synthetic Candidate",
                List.of(
                        new GermanCoverLetterContent.PersonalField("Web", "http://example.test/" + "a".repeat(45)),
                        new GermanCoverLetterContent.PersonalField("GitHub", "https://example.test/" + "b".repeat(45)),
                        new GermanCoverLetterContent.PersonalField("Kennung", longToken)
                ),
                null,
                null,
                "Backend Engineer",
                "Bewerbung als Backend Engineer",
                "Sehr geehrte Damen und Herren,",
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(longToken),
                        java.util.stream.IntStream.range(0, 17)
                                .mapToObj(index -> "Absatz " + index + " mit " + longToken + " und belastbarer Erfahrung.")
                ).toList(),
                "Mit freundlichen Grüßen,",
                "Synthetic Candidate"
        );

        var result = new GermanCoverLetterPdfRenderer(tempDir).generate("two pages", twoPage);

        assertEquals(2, result.pageCount());
        assertTrue(result.fileName().endsWith(".pdf"));
        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Seite 1 / 2"));
            assertTrue(text.contains("Seite 2 / 2"));
        }
    }

    @Test
    void supportsDefaultAndFallbackFileNamesAndSanitizesUnsupportedText() throws Exception {
        GermanCoverLetterContent minimal = new GermanCoverLetterContent(
                "Synthetic 😀 Candidate", List.of(), null, null, "Engineer",
                "Bewerbung als Engineer", "Sehr geehrte Damen und Herren,",
                List.of("\u0000", "Ein ausreichend langer zweiter Absatz für die direkte Rendering-Prüfung."),
                "Mit freundlichen Grüßen,", "Synthetic Candidate"
        );
        GermanCoverLetterPdfRenderer renderer = new GermanCoverLetterPdfRenderer(tempDir);

        var defaultName = renderer.generate(null, minimal);
        var dotName = renderer.generate(".", minimal);
        var doubleDotName = renderer.generate("..", minimal);

        assertEquals("cover-letter.pdf", defaultName.fileName());
        assertEquals("cover-letter.pdf", dotName.fileName());
        assertEquals("cover-letter.pdf", doubleDotName.fileName());
        try (PDDocument document = Loader.loadPDF(Path.of(dotName.path()).toFile())) {
            assertTrue(new PDFTextStripper().getText(document).contains("Synthetic ? Candidate"));
        }
    }

    @Test
    void rejectsUnreadableLongLinkSingleBlockOverflowInvalidNameAndIoFailure() throws Exception {
        GermanCoverLetterPdfRenderer renderer = new GermanCoverLetterPdfRenderer(tempDir);
        GermanCoverLetterContent unreadableLink = withFields(List.of(
                new GermanCoverLetterContent.PersonalField("Link", "https://example.test/" + "x".repeat(600))
        ), List.of("Ein erster ausreichend langer Absatz.", "Ein zweiter ausreichend langer Absatz."));
        assertThrows(ApplicationException.class, () -> renderer.generate("link.pdf", unreadableLink));

        GermanCoverLetterContent tallBlock = withFields(List.of(), List.of("Wort ".repeat(2_000), "Zweiter Absatz."));
        assertThrows(ApplicationException.class, () -> renderer.generate("tall.pdf", tallBlock));
        assertThrows(ApplicationException.class, () -> renderer.generate("bad\0name", content()));
        assertThrows(NullPointerException.class, () -> new GermanCoverLetterPdfRenderer(null));
        assertThrows(NullPointerException.class, () -> renderer.generate("null.pdf", null));

        Path outputIsFile = Files.writeString(tempDir.resolve("output-file"), "not a directory");
        ApplicationException ioFailure = assertThrows(ApplicationException.class,
                () -> new GermanCoverLetterPdfRenderer(outputIsFile).generate("letter.pdf", content()));
        assertNotNull(ioFailure.getCause());
        assertFalse(Files.exists(outputIsFile.resolve("letter.pdf")));
    }

    private static GermanCoverLetterContent withFields(
            List<GermanCoverLetterContent.PersonalField> fields,
            List<String> paragraphs
    ) {
        return new GermanCoverLetterContent(
                "Synthetic Candidate", fields, "Example GmbH", "Berlin", "Backend Engineer",
                "Bewerbung als Backend Engineer", "Sehr geehrte Damen und Herren,", paragraphs,
                "Mit freundlichen Grüßen,", "Synthetic Candidate"
        );
    }

    private static GermanCoverLetterContent content() {
        return new GermanCoverLetterContent(
                "Synthetic Candidate",
                List.of(
                        new GermanCoverLetterContent.PersonalField("E-Mail", "candidate@example.test"),
                        new GermanCoverLetterContent.PersonalField("Telefon", "+49 555 0100"),
                        new GermanCoverLetterContent.PersonalField("GitHub", "https://example.test/synthetic")
                ),
                "Example GmbH",
                "Berlin",
                "Backend Engineer",
                "Bewerbung als Backend Engineer bei Example GmbH",
                "Sehr geehrte Damen und Herren,",
                List.of(
                        "Mit großem Interesse bewerbe ich mich als Backend Engineer bei Example GmbH.",
                        "In meiner bisherigen Tätigkeit als Java Developer bei Example Systems habe ich praktische Erfahrung gesammelt.",
                        "Für diese Position bringe ich insbesondere Kenntnisse in Java und PostgreSQL mit."
                ),
                "Mit freundlichen Grüßen,",
                "Synthetic Candidate"
        );
    }
}
