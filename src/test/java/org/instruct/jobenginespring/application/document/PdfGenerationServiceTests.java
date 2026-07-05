package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.text.PDFTextStripper;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratePdfFileRequest;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
            String chromeText = "Styled Report | Page 1 of 1";
            assertEquals(2, countOccurrences(text, chromeText));
            assertTrue(text.contains("SUMMARY"));
            assertTrue(text.contains("Short body for a styled generated PDF."));

            String fillColors = fillColors(document);
            assertTrue(hasNonStrokingColor(document, 255), () -> "page background should use white fill color; colors=" + fillColors);
            assertTrue(hasNonStrokingColor(document, 64), () -> "header/footer chrome should use grey fill color; colors=" + fillColors);
            assertTrue(hasStrokingColor(document, 64), () -> "section separator should use grey stroke color; colors=" + fillColors);
            assertTrue(hasStrokeOperator(document), "section separator should draw a stroked line");
        }
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

    private static boolean hasStrokingColor(PDDocument document, int rgbChannel) throws IOException {
        return hasColor(document, rgbChannel, "RG", "SC");
    }

    private static boolean hasColor(PDDocument document, int rgbChannel, String rgbOperator, String colorSpaceOperator) throws IOException {
        float expected = rgbChannel / 255.0f;
        List<Object> tokens = new PDFStreamParser(document.getPage(0)).parse();
        for (int index = 3; index < tokens.size(); index++) {
            if (tokens.get(index) instanceof Operator operator
                    && (rgbOperator.equals(operator.getName()) || colorSpaceOperator.equals(operator.getName()))
                    && tokens.get(index - 3) instanceof COSNumber red
                    && tokens.get(index - 2) instanceof COSNumber green
                    && tokens.get(index - 1) instanceof COSNumber blue
                    && closeTo(red.floatValue(), expected)
                    && closeTo(green.floatValue(), expected)
                    && closeTo(blue.floatValue(), expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStrokeOperator(PDDocument document) throws IOException {
        return new PDFStreamParser(document.getPage(0)).parse().stream()
                .anyMatch(token -> token instanceof Operator operator && "S".equals(operator.getName()));
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
}
