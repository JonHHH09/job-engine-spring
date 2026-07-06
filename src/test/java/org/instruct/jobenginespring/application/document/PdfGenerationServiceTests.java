package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.text.PDFTextStripper;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratePdfFileRequest;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfGenerationServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void generatesPdfInConfiguredTemporaryDirectory() throws IOException {
        PdfGenerationService service = new PdfGenerationService(tempDir);

        GeneratedPdfFileResult result = service.generatePdfFile(new GeneratePdfFileRequest(
                "sample-report",
                "Sample Report",
                "Hello from the job-engine-spring MCP generated PDF."
        ));

        Path generatedPath = Path.of(result.path());
        assertEquals(tempDir, generatedPath.getParent());
        assertEquals("sample-report.pdf", result.fileName());
        assertTrue(Files.isRegularFile(generatedPath));
        assertTrue(Files.size(generatedPath) > 0);
        assertEquals(Files.size(generatedPath), result.byteSize());
        assertEquals(1, result.pageCount());
        assertFalse(result.generatedAt().isBlank());
        assertTrue(new String(Files.readAllBytes(generatedPath), 0, 5).startsWith("%PDF-"));
    }

    @Test
    void sanitizesFileNameAndForcesPdfExtension() throws IOException {
        PdfGenerationService service = new PdfGenerationService(tempDir);

        GeneratedPdfFileResult result = service.generatePdfFile(new GeneratePdfFileRequest(
                "../Unsafe Name?.txt",
                "Title",
                "Body"
        ));

        assertEquals("Unsafe_Name_.txt.pdf", result.fileName());
        assertEquals(tempDir.resolve("Unsafe_Name_.txt.pdf"), Path.of(result.path()));
        assertTrue(Files.isRegularFile(Path.of(result.path())));
    }

    @Test
    void createsAdditionalPagesForLongBodies() throws IOException {
        PdfGenerationService service = new PdfGenerationService(tempDir);
        String longBody = ("This is a repeated line for multi-page PDF generation.\n").repeat(80);

        GeneratedPdfFileResult result = service.generatePdfFile(new GeneratePdfFileRequest(
                "long-report.pdf",
                "Long Report",
                longBody
        ));

        assertTrue(result.pageCount() > 1);
        assertTrue(Files.isRegularFile(Path.of(result.path())));
    }

    @Test
    void generatedPdfUsesWhiteBackgroundChromeSectionSeparatorsAndPageNumbers() throws IOException {
        PdfGenerationService service = new PdfGenerationService(tempDir);

        GeneratedPdfFileResult result = service.generatePdfFile(new GeneratePdfFileRequest(
                "styled-report.pdf",
                "Styled Report",
                "SUMMARY\nShort body for a styled generated PDF."
        ));

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            String chromeText = "Page 1 of 1";
            assertEquals(2, countOccurrences(text, chromeText));
            assertEquals(1, countOccurrences(text, "Styled Report"));
            assertTrue(hasTextOffsetAtOrBeyond(document, chromeText, 500), "header/footer page numbers should be right aligned");
            assertTrue(text.contains("SUMMARY"));
            assertTrue(text.contains("Short body for a styled generated PDF."));

            String fillColors = fillColors(document);
            assertTrue(hasNonStrokingColor(document, 255), () -> "page background should use white fill color; colors=" + fillColors);
            assertTrue(hasNonStrokingColor(document, 64), () -> "header/footer chrome should use chrome fill color; colors=" + fillColors);
            assertTrue(hasNonStrokingColor(document, 255), () -> "header/footer text should use white fill color; colors=" + fillColors);
            assertTrue(hasStrokingColor(document, 64), () -> "section separator should use chrome stroke color; colors=" + fillColors);
            assertTrue(hasStrokeOperator(document), "section separator should draw a stroked line");
        }
    }

    @Test
    void generatedPdfRendersBulletsWithMarkerAndHangingIndent() throws Exception {
        PdfGenerationService service = new PdfGenerationService(tempDir);
        String longBullet = "Built a long deterministic resume bullet that should wrap onto a continuation line while retaining visual bullet formatting and indentation for readable Canadian resume experience sections.";

        GeneratedPdfFileResult result = service.generatePdfFile(new GeneratePdfFileRequest(
                "bullet-report.pdf",
                "Bullet Report",
                "EXPERIENCE\n- " + longBullet
        ));

        @SuppressWarnings("unchecked")
        List<String> bodyLines = (List<String>) invoke("bodyLines", new Class<?>[]{String.class, String.class}, "Bullet Report", "EXPERIENCE\n- " + longBullet);
        assertTrue(bodyLines.stream().anyMatch(line -> line.startsWith("- Built a long deterministic")));
        assertTrue(bodyLines.stream().anyMatch(line -> line.startsWith("  ") && line.contains("experience sections.")));

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("•"), "bullet marker should be rendered as a real bullet glyph");
            assertFalse(text.contains("- Built a long deterministic"), "hyphen should not be rendered as the visible bullet marker");
        }
    }

    @Test
    void generatedPdfRendersLabeledSkillCategoriesBoldAndWrapsContinuationsWithoutSeparators() throws Exception {
        PdfGenerationService service = new PdfGenerationService(tempDir);
        String skills = "TECHNICAL SKILLS\nCloud / Security: AWS S3, CloudFront, Docker, Docker Compose, Supabase, Vercel, CI/CD, OAuth2, JWT, RBAC, MFA, IAM";

        GeneratedPdfFileResult result = service.generatePdfFile(new GeneratePdfFileRequest(
                "skills-report.pdf",
                "Skills Report",
                skills
        ));

        @SuppressWarnings("unchecked")
        List<String> bodyLines = (List<String>) invoke("bodyLines", new Class<?>[]{String.class, String.class}, "Skills Report", skills);
        assertTrue(bodyLines.stream().anyMatch(line -> line.startsWith("Cloud / Security:")));
        assertTrue(bodyLines.stream().anyMatch(line -> line.startsWith("  ") && line.contains("RBAC")));
        assertTrue((Boolean) invoke("isLabeledLine", new Class<?>[]{String.class}, "Cloud / Security: AWS S3"));

        try (PDDocument document = Loader.loadPDF(Path.of(result.path()).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Cloud / Security:"));
            assertTrue(text.contains("RBAC"));
            assertEquals(1, countStrokeOperators(document), "only the TECHNICAL SKILLS heading should draw a separator");
        }
    }

    @Test
    void paginationKeepsSectionHeadingWithFollowingContent() throws Exception {
        int linesPerPage = firstPageLineCountForGeneratedFiller();
        List<String> lines = new java.util.ArrayList<>();
        for (int index = 0; index < linesPerPage - 1; index++) {
            lines.add("Filler " + index);
        }
        lines.add("SUMMARY");
        lines.add("Section body should start below its heading.");

        @SuppressWarnings("unchecked")
        List<Object> pages = (List<Object>) invoke("paginate", new Class<?>[]{List.class}, lines);

        assertEquals(linesPerPage - 1, pageLines(pages.getFirst()).size());
        assertEquals("SUMMARY", pageLines(pages.get(1)).getFirst());
        assertEquals("Section body should start below its heading.", pageLines(pages.get(1)).get(1));
    }

    @Test
    void paginationKeepsResumeEntryHeadingDateAndFirstDetailTogether() throws Exception {
        int linesPerPage = firstPageLineCountForGeneratedFiller();
        List<String> lines = new java.util.ArrayList<>();
        for (int index = 0; index < linesPerPage - 2; index++) {
            lines.add("Filler " + index);
        }
        lines.add("Developer | Example Co");
        lines.add("2024-01 - Present | Remote");
        lines.add("- Built deterministic resume layout checks.");

        @SuppressWarnings("unchecked")
        List<Object> pages = (List<Object>) invoke("paginate", new Class<?>[]{List.class}, lines);

        assertEquals(linesPerPage - 2, pageLines(pages.getFirst()).size());
        assertEquals("Developer | Example Co", pageLines(pages.get(1)).getFirst());
        assertEquals("2024-01 - Present | Remote", pageLines(pages.get(1)).get(1));
        assertEquals("- Built deterministic resume layout checks.", pageLines(pages.get(1)).get(2));
    }

    @Test
    void rejectsBlankBody() {
        PdfGenerationService service = new PdfGenerationService(tempDir);

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.generatePdfFile(new GeneratePdfFileRequest("sample.pdf", "Title", " "))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("body", exception.details().get("field"));
        assertEquals("must not be blank", exception.details().get("reason"));
    }

    @Test
    void rejectsBodyAboveLimit() {
        PdfGenerationService service = new PdfGenerationService(tempDir);
        String oversizedBody = "x".repeat(PdfGenerationService.MAX_BODY_CHARACTERS + 1);

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.generatePdfFile(new GeneratePdfFileRequest("sample.pdf", "Title", oversizedBody))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("body", exception.details().get("field"));
        assertEquals(
                "must be less than or equal to " + PdfGenerationService.MAX_BODY_CHARACTERS + " characters",
                exception.details().get("reason")
        );
    }

    @Test
    void usesDefaultOutputDirectoryForBlankConfigurationAndRejectsInvalidConfiguration() {
        PdfGenerationService defaultService = new PdfGenerationService(" ");

        GeneratedPdfFileResult result = defaultService.generatePdfFile(new GeneratePdfFileRequest(
                null,
                null,
                "Body"
        ));

        assertEquals("generated.pdf", result.fileName());
        assertTrue(Path.of(result.path()).endsWith("tmp/generated-pdfs/generated.pdf"));

        PdfGenerationService nullDefaultService = new PdfGenerationService((String) null);
        GeneratedPdfFileResult nullDefaultResult = nullDefaultService.generatePdfFile(new GeneratePdfFileRequest(
                "null-default",
                "Title",
                "Body"
        ));
        assertTrue(Path.of(nullDefaultResult.path()).endsWith("tmp/generated-pdfs/null-default.pdf"));

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> new PdfGenerationService("bad\0path")
        );
        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("outputDirectory", exception.details().get("field"));
    }

    @Test
    void rejectsInvalidFileNameConfiguration() throws Exception {
        PdfGenerationService service = new PdfGenerationService(tempDir);

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.generatePdfFile(new GeneratePdfFileRequest("bad\0name", "Title", "Body"))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("fileName", exception.details().get("field"));

        PdfGenerationService guardedService = new PdfGenerationService(tempDir.resolve("base"));
        Field outputDirectory = PdfGenerationService.class.getDeclaredField("outputDirectory");
        outputDirectory.setAccessible(true);
        outputDirectory.set(guardedService, tempDir.resolve("base").resolve(".."));
        ApplicationException escaped = assertThrows(
                ApplicationException.class,
                () -> guardedService.generatePdfFile(new GeneratePdfFileRequest("sample.pdf", "Title", "Body"))
        );
        assertEquals("fileName", escaped.details().get("field"));
    }

    @Test
    void coversPdfTextSanitizingWrappingAndChromeFittingEdges() throws Exception {
        assertEquals("generated.pdf", invokeString("sanitizeFileName", new Class<?>[]{String.class}, " "));
        assertEquals("generated.pdf", invokeString("sanitizeFileName", new Class<?>[]{String.class}, "/"));
        assertEquals("generated.pdf", invokeString("sanitizeFileName", new Class<?>[]{String.class}, "."));
        assertEquals("generated.pdf", invokeString("sanitizeFileName", new Class<?>[]{String.class}, ".."));
        assertEquals("Generated PDF", invokeString("resolveTitle", new Class<?>[]{String.class}, "\t"));
        assertEquals("A B ?", invokeString("sanitizeText", new Class<?>[]{String.class}, "A\tB\n\u2603"));
        assertThrows(ApplicationException.class, () -> invokeString("validateBody", new Class<?>[]{String.class}, new Object[]{null}));

        @SuppressWarnings("unchecked")
        List<String> wrapped = (List<String>) invoke("wrap", new Class<?>[]{String.class, int.class}, "abcdefghij", 4);
        assertEquals(List.of("abcd", "efgh", "ij"), wrapped);
        @SuppressWarnings("unchecked")
        List<String> wrappedAtSpace = (List<String>) invoke("wrap", new Class<?>[]{String.class, int.class}, "abc def ghi", 7);
        assertEquals("abc def", wrappedAtSpace.getFirst());
        assertEquals("ghi", wrappedAtSpace.get(1));
        @SuppressWarnings("unchecked")
        List<String> wrappedBullet = (List<String>) invoke("wrapBullet", new Class<?>[]{String.class, int.class}, "- abc def ghi", 7);
        assertEquals(List.of("- abc", "  def", "  ghi"), wrappedBullet);
        @SuppressWarnings("unchecked")
        List<Object> emptyPages = (List<Object>) invoke("paginate", new Class<?>[]{List.class}, List.of());
        assertEquals(1, emptyPages.size());

        assertFalse((Boolean) invoke("shouldStartNewPage", new Class<?>[]{List.class, int.class, int.class, int.class}, List.of("SUMMARY", "Body"), 0, 0, 2));
        assertTrue((Boolean) invoke("shouldStartNewPage", new Class<?>[]{List.class, int.class, int.class, int.class}, List.of("SUMMARY", "Body"), 0, 1, 2));
        assertFalse((Boolean) invoke("isResumeEntryHeading", new Class<?>[]{List.class, int.class}, List.of("Developer | Example Co"), 0));
        assertFalse((Boolean) invoke("isResumeEntryHeading", new Class<?>[]{List.class, int.class}, java.util.Arrays.asList(null, "2024-01 - Present"), 0));
        assertFalse((Boolean) invoke("isResumeEntryHeading", new Class<?>[]{List.class, int.class}, List.of("Plain heading", "2024-01 - Present"), 0));
        assertFalse((Boolean) invoke("isResumeEntryHeading", new Class<?>[]{List.class, int.class}, List.of("Backend: Java | Spring", "2024-01 - Present"), 0));
        assertFalse((Boolean) invoke("isResumeEntryHeading", new Class<?>[]{List.class, int.class}, List.of("ROLE | COMPANY", "2024-01 - Present"), 0));
        assertFalse((Boolean) invoke("isResumeEntryHeading", new Class<?>[]{List.class, int.class}, List.of("Developer | Example Co", "plain detail"), 0));
        assertFalse((Boolean) invoke("isResumeDateLine", new Class<?>[]{String.class}, new Object[]{null}));
        assertTrue((Boolean) invoke("isResumeDateLine", new Class<?>[]{String.class}, "Dates not provided"));
        assertTrue((Boolean) invoke("isResumeDateLine", new Class<?>[]{String.class}, "Unknown - 2024-01"));
        assertTrue((Boolean) invoke("isResumeDateLine", new Class<?>[]{String.class}, "2024-01 - Present | Remote"));
        assertFalse((Boolean) invoke("isResumeDateLine", new Class<?>[]{String.class}, "Spring Boot - Java"));

        assertFalse((Boolean) invoke("isSectionHeading", new Class<?>[]{String.class}, new Object[]{null}));
        assertFalse((Boolean) invoke("isSectionHeading", new Class<?>[]{String.class}, "SECTION: VALUE"));
        assertFalse((Boolean) invoke("isSectionHeading", new Class<?>[]{String.class}, "1234"));
        assertFalse((Boolean) invoke("isBulletLine", new Class<?>[]{String.class}, new Object[]{null}));
        assertFalse((Boolean) invoke("isBulletLine", new Class<?>[]{String.class}, "plain line"));
        assertTrue((Boolean) invoke("isBulletLine", new Class<?>[]{String.class}, "  - indented bullet"));
        assertFalse((Boolean) invoke("isBulletContinuationLine", new Class<?>[]{String.class}, new Object[]{null}));
        assertFalse((Boolean) invoke("isBulletContinuationLine", new Class<?>[]{String.class}, "  "));
        assertFalse((Boolean) invoke("isBulletContinuationLine", new Class<?>[]{String.class}, "normal line"));
        assertTrue((Boolean) invoke("isBulletContinuationLine", new Class<?>[]{String.class}, "  continuation"));
        assertFalse((Boolean) invoke("isLabeledLine", new Class<?>[]{String.class}, new Object[]{null}));
        assertFalse((Boolean) invoke("isLabeledLine", new Class<?>[]{String.class}, " "));
        assertFalse((Boolean) invoke("isLabeledLine", new Class<?>[]{String.class}, ": missing label"));
        assertFalse((Boolean) invoke("isLabeledLine", new Class<?>[]{String.class}, "This label is intentionally longer than thirty two chars: value"));
        assertEquals("plain line", invokeString("stripBulletPrefix", new Class<?>[]{String.class}, "plain line"));

        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        assertEquals("Short", invokeString("fitText", new Class<?>[]{String.class, PDType1Font.class, float.class, float.class}, "Short", font, 9.0f, 500.0f));
        assertEquals("Very...", invokeString("fitText", new Class<?>[]{String.class, PDType1Font.class, float.class, float.class}, "Very long title", font, 9.0f, 30.0f));
        assertEquals("...", invokeString("fitText", new Class<?>[]{String.class, PDType1Font.class, float.class, float.class}, "Very long title", font, 9.0f, 1.0f));
    }

    @Test
    void mapsIoFailuresToInternalErrors() throws IOException {
        Path notADirectory = Files.createTempFile(tempDir, "not-a-directory", ".tmp");
        PdfGenerationService service = new PdfGenerationService(notADirectory);

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.generatePdfFile(new GeneratePdfFileRequest("sample.pdf", "Title", "Body"))
        );

        assertEquals("internal_error", exception.errorCode().code());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static boolean hasNonStrokingColor(PDDocument document, int rgbChannel) throws IOException {
        return hasColor(document, rgbChannel, "rg", "sc");
    }

    private static boolean hasNonStrokingColor(PDDocument document, int red, int green, int blue) throws IOException {
        return hasColor(document, red, green, blue, "rg", "sc");
    }

    private static boolean hasStrokingColor(PDDocument document, int rgbChannel) throws IOException {
        return hasColor(document, rgbChannel, "RG", "SC");
    }

    private static boolean hasStrokingColor(PDDocument document, int red, int green, int blue) throws IOException {
        return hasColor(document, red, green, blue, "RG", "SC");
    }

    private static boolean hasColor(PDDocument document, int rgbChannel, String rgbOperator, String colorSpaceOperator) throws IOException {
        return hasColor(document, rgbChannel, rgbChannel, rgbChannel, rgbOperator, colorSpaceOperator);
    }

    private static boolean hasColor(PDDocument document, int redChannel, int greenChannel, int blueChannel, String rgbOperator, String colorSpaceOperator) throws IOException {
        float expectedRed = redChannel / 255.0f;
        float expectedGreen = greenChannel / 255.0f;
        float expectedBlue = blueChannel / 255.0f;
        List<Object> tokens = new PDFStreamParser(document.getPage(0)).parse();
        for (int index = 3; index < tokens.size(); index++) {
            if (tokens.get(index) instanceof Operator operator
                    && (rgbOperator.equals(operator.getName()) || colorSpaceOperator.equals(operator.getName()))
                    && tokens.get(index - 3) instanceof COSNumber red
                    && tokens.get(index - 2) instanceof COSNumber green
                    && tokens.get(index - 1) instanceof COSNumber blue
                    && closeTo(red.floatValue(), expectedRed)
                    && closeTo(green.floatValue(), expectedGreen)
                    && closeTo(blue.floatValue(), expectedBlue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStrokeOperator(PDDocument document) throws IOException {
        return countStrokeOperators(document) > 0;
    }

    private static long countStrokeOperators(PDDocument document) throws IOException {
        return new PDFStreamParser(document.getPage(0)).parse().stream()
                .filter(token -> token instanceof Operator operator && "S".equals(operator.getName()))
                .count();
    }

    private static boolean hasTextOffsetAtOrBeyond(PDDocument document, String text, float minimumX) throws IOException {
        List<Object> tokens = new PDFStreamParser(document.getPage(0)).parse();
        for (int index = 3; index < tokens.size(); index++) {
            if (tokens.get(index) instanceof Operator operator
                    && "Tj".equals(operator.getName())
                    && tokens.get(index - 1) instanceof COSString string
                    && text.equals(string.getString())) {
                for (int previous = index - 2; previous >= 2; previous--) {
                    if (tokens.get(previous) instanceof Operator offsetOperator && "Td".equals(offsetOperator.getName())) {
                        if (tokens.get(previous - 2) instanceof COSNumber x && x.floatValue() >= minimumX) {
                            return true;
                        }
                        break;
                    }
                }
            }
        }
        return false;
    }

    private static String fillColors(PDDocument document) throws IOException {
        List<Object> tokens = new PDFStreamParser(document.getPage(0)).parse();
        StringBuilder colors = new StringBuilder();
        for (int index = 3; index < tokens.size(); index++) {
            if (tokens.get(index) instanceof Operator operator
                    && ("rg".equals(operator.getName()) || "sc".equals(operator.getName()))
                    && tokens.get(index - 3) instanceof COSNumber red
                    && tokens.get(index - 2) instanceof COSNumber green
                    && tokens.get(index - 1) instanceof COSNumber blue) {
                colors.append(operator.getName())
                        .append('(')
                        .append(red.floatValue())
                        .append(',')
                        .append(green.floatValue())
                        .append(',')
                        .append(blue.floatValue())
                        .append(") ");
            }
        }
        return colors.toString();
    }

    private static boolean closeTo(float actual, float expected) {
        return Math.abs(actual - expected) < 0.001f;
    }

    private static int firstPageLineCountForGeneratedFiller() throws Exception {
        List<String> filler = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "Filler " + index)
                .toList();
        @SuppressWarnings("unchecked")
        List<Object> pages = (List<Object>) invoke("paginate", new Class<?>[]{List.class}, filler);
        return pageLines(pages.getFirst()).size();
    }

    @SuppressWarnings("unchecked")
    private static List<String> pageLines(Object pageLines) throws Exception {
        Method method = pageLines.getClass().getDeclaredMethod("lines");
        method.setAccessible(true);
        return (List<String>) method.invoke(pageLines);
    }

    private static String invokeString(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        return (String) invoke(methodName, parameterTypes, args);
    }

    private static Object invoke(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = PdfGenerationService.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
