package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratePdfFileRequest;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
