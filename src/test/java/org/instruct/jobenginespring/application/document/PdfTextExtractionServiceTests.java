package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.ExtractedPdfPage;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionRequest;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextExtractionServiceTests {

    private final PdfTextExtractionService service = new PdfTextExtractionService();

    @TempDir
    Path tempDir;

    @Test
    void rejectsRawPdfPathsOutsideConfiguredImportRoot() throws IOException {
        Path importRoot = tempDir.resolve("imports");
        Files.createDirectories(importRoot);
        Path outsideRoot = tempDir.resolve("outside.pdf");
        Files.writeString(outsideRoot, "%PDF-1.3\noutside");
        PdfTextExtractionService rootedService = new PdfTextExtractionService(importRoot.toString());

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> rootedService.extractText(new PdfTextExtractionRequest(outsideRoot.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("file must be under configured import root", exception.details().get("reason"));
    }

    @Test
    void rejectsBlankPath() {
        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(" ", null, null))
        );
        ApplicationException nullPath = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(null, null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("path", exception.details().get("field"));
        assertEquals("must not be blank", exception.details().get("reason"));
        assertEquals("must not be blank", nullPath.details().get("reason"));
    }

    @Test
    void rejectsMissingFile() {
        Path missingPdf = tempDir.resolve("missing.pdf");

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(missingPdf.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("file was not found", exception.details().get("reason"));
    }

    @Test
    void rejectsDirectoryPath() {
        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(tempDir.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("must identify a regular file", exception.details().get("reason"));
    }

    @Test
    void rejectsNonPdfFileEvenWhenExtensionMatches() throws IOException {
        Path fakePdf = tempDir.resolve("fake.pdf");
        Files.writeString(fakePdf, "not really a PDF");

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(fakePdf.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("file must be a PDF document", exception.details().get("reason"));
    }

    @Test
    void pdfHeaderValidationReadsOnlyTheMagicBytes() {
        byte[] headerAndPrivateContent = "%PDF-private-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InputStream bounded = new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                if (index >= 5) {
                    throw new IOException("read beyond PDF magic header");
                }
                return headerAndPrivateContent[index++];
            }
        };

        assertTrue(PdfTextExtractionService.hasPdfHeader(bounded));
    }

    @Test
    void storedPdfExtractionUsesImmutableStreamAndValidatesActualContentSize() throws IOException {
        Path pdf = createPdf("stored-stream.pdf", List.of("streamed text"));
        byte[] content = Files.readAllBytes(pdf);
        StoredDocumentFile stored = storedFile(content, content.length);

        PdfTextExtractionResult result = service.extractText(stored, null, false);

        assertEquals("stored.pdf", result.fileName());
        assertTrue(result.text().contains("streamed text"));
        assertEquals(List.of(), result.pages());
        assertThrows(NullPointerException.class, () -> service.extractText((StoredDocumentFile) null, null, false));
        assertEquals("file must be a PDF document", assertThrows(
                ApplicationException.class,
                () -> service.extractText(storedFile("not pdf".getBytes(), 7), null, false)
        ).details().get("reason"));
        byte[] oversizedContent = new byte[Math.toIntExact(PdfTextExtractionService.MAX_FILE_BYTES + 1)];
        System.arraycopy("%PDF-".getBytes(), 0, oversizedContent, 0, 5);
        assertEquals(
                "PDF file size exceeds limit of " + PdfTextExtractionService.MAX_FILE_BYTES + " bytes",
                assertThrows(ApplicationException.class, () -> service.extractText(
                        storedFile(oversizedContent, oversizedContent.length), null, false
                )).details().get("reason")
        );
    }

    @Test
    void localPathSnapshotRemainsStableWhenFileIsReplacedBeforeParsing() throws IOException {
        Path original = createPdf("replaceable.pdf", List.of("original snapshot text"));
        Path replacement = createPdf("replacement.pdf", List.of("replacement text"));
        PdfTextExtractionService.PdfContentSnapshot snapshot =
                PdfTextExtractionService.readLocalPdfSnapshot(original);

        Files.move(replacement, original, StandardCopyOption.REPLACE_EXISTING);
        assertEquals("replaceable.pdf", snapshot.getDescription());
        assertTrue(snapshot.contentLength() > 0);
        PdfTextExtractionResult result = service.extractText(snapshot, null, null);

        assertTrue(result.text().contains("original snapshot text"));
        assertFalse(result.text().contains("replacement text"));
        assertEquals(1, result.pages().size());
    }

    @Test
    void storedDocumentResourceReportsImmutableMetadata() throws IOException {
        StoredDocumentFile stored = storedFile("%PDF-private".getBytes(), 12);
        PdfTextExtractionService.StoredDocumentResource resource =
                new PdfTextExtractionService.StoredDocumentResource(stored);

        assertEquals("stored.pdf", resource.getDescription());
        assertEquals("stored.pdf", resource.getFilename());
        assertEquals(12, resource.contentLength());
        assertArrayEquals(stored.content(), resource.getInputStream().readAllBytes());
    }

    @Test
    void reportsUnreadableHeaderStream() {
        InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("private detail");
            }
        };

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> PdfTextExtractionService.hasPdfHeader(unreadable)
        );

        assertEquals("file is not readable", exception.details().get("reason"));
    }

    @Test
    void rejectsInvalidMaxCharacters() throws IOException {
        Path pdf = createPdf("sample.pdf", List.of("Hello from job-engine-spring PDF fixture"));

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(pdf.toString(), 0, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("maxCharacters", exception.details().get("field"));
        assertEquals("must be greater than zero", exception.details().get("reason"));
    }

    @Test
    void rejectsMaxCharactersAboveLimit() throws IOException {
        Path pdf = createPdf("sample.pdf", List.of("Hello from job-engine-spring PDF fixture"));

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(
                        pdf.toString(),
                        PdfTextExtractionService.MAX_CHARACTERS_LIMIT + 1,
                        null
                ))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("maxCharacters", exception.details().get("field"));
        assertEquals(
                "must be less than or equal to " + PdfTextExtractionService.MAX_CHARACTERS_LIMIT,
                exception.details().get("reason")
        );
    }

    @Test
    void rejectsOversizedPdfBeforeParsing() throws IOException {
        Path pdf = createOversizedPdfLikeFile("oversized.pdf");

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(pdf.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("path", exception.details().get("field"));
        assertEquals(
                "PDF file size exceeds limit of " + PdfTextExtractionService.MAX_FILE_BYTES + " bytes",
                exception.details().get("reason")
        );
    }

    @Test
    void rejectsPdfAbovePageLimitBeforeTextExtraction() throws IOException {
        Path pdf = createBlankPdf("too-many-pages.pdf", PdfTextExtractionService.MAX_PAGE_COUNT + 1);

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(pdf.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("path", exception.details().get("field"));
        assertEquals(
                "PDF page count exceeds limit of " + PdfTextExtractionService.MAX_PAGE_COUNT,
                exception.details().get("reason")
        );
    }

    @Test
    void rejectsMalformedPdfWithValidHeaderUsingSanitizedError() throws IOException {
        Path pdf = tempDir.resolve("malformed.pdf");
        Files.writeString(pdf, "%PDF-this is not a complete PDF");

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(pdf.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("PDF text could not be extracted", exception.getMessage());
        assertEquals("path", exception.details().get("field"));
        assertEquals("PDF text could not be extracted", exception.details().get("reason"));
    }

    @Test
    void rejectsEncryptedPdfWithSanitizedError() throws IOException {
        Path pdf = createEncryptedPdf("encrypted.pdf", "private text");

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(pdf.toString(), null, null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("PDF text could not be extracted", exception.getMessage());
        assertEquals("path", exception.details().get("field"));
        assertEquals("PDF text could not be extracted", exception.details().get("reason"));
    }

    @Test
    void extractsTextFromPdfWithPageMetadata() throws IOException {
        Path pdf = createPdf("sample-text.pdf", List.of(
                "Hello from job-engine-spring PDF fixture",
                "Second page extraction text"
        ));

        PdfTextExtractionResult result = service.extractText(
                new PdfTextExtractionRequest(pdf.toString(), null, true)
        );

        assertEquals("sample-text.pdf", result.fileName());
        assertEquals(2, result.pageCount());
        assertFalse(result.truncated());
        assertTrue(result.text().contains("Hello from job-engine-spring PDF fixture"));
        assertTrue(result.text().contains("Second page extraction text"));
        assertEquals(2, result.pages().size());
        assertEquals(new ExtractedPdfPage(1, "Hello from job-engine-spring PDF fixture"), result.pages().getFirst());
        assertEquals(new ExtractedPdfPage(2, "Second page extraction text"), result.pages().get(1));
    }

    @Test
    void canOmitPageBreakdown() throws IOException {
        Path pdf = createPdf("sample-text.pdf", List.of("Only full text is needed"));

        PdfTextExtractionResult result = service.extractText(
                new PdfTextExtractionRequest(pdf.toString(), null, false)
        );

        assertTrue(result.text().contains("Only full text is needed"));
        assertEquals(List.of(), result.pages());
    }

    @Test
    void extractsTextFromStoredPdfBytes() throws IOException {
        Path pdf = createPdf("stored.pdf", List.of("Stored PDF byte extraction text"));

        PdfTextExtractionResult result = service.extractText(Files.readAllBytes(pdf), "stored.pdf", null, false);

        assertEquals("stored.pdf", result.fileName());
        assertEquals(1, result.pageCount());
        assertTrue(result.text().contains("Stored PDF byte extraction text"));
        assertEquals(List.of(), result.pages());
    }

    @Test
    void storedPdfByteExtractionUsesSafeDefaultFileNames() throws IOException {
        Path pdf = createPdf("stored.pdf", List.of("Stored PDF byte extraction text"));
        byte[] content = Files.readAllBytes(pdf);

        assertEquals("stored.pdf", service.extractText(content, null, null, false).fileName());
        assertEquals("stored.pdf", service.extractText(content, " ", null, false).fileName());
    }

    @Test
    void rejectsInvalidStoredPdfBytes() {
        ApplicationException shortContent = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new byte[]{'%', 'P', 'D'}, "stored.pdf", null, false)
        );
        assertEquals("file must be a PDF document", shortContent.details().get("reason"));

        ApplicationException nonPdf = assertThrows(
                ApplicationException.class,
                () -> service.extractText("not a pdf".getBytes(), "stored.pdf", null, false)
        );
        assertEquals("file must be a PDF document", nonPdf.details().get("reason"));
    }

    @Test
    void rejectsInvalidAndShortPdfPaths() throws IOException {
        ApplicationException invalidPath = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest("bad\0path", null, null))
        );
        assertEquals("must be a valid file path", invalidPath.details().get("reason"));

        Path shortPdf = tempDir.resolve("short.pdf");
        Files.writeString(shortPdf, "%PD");
        ApplicationException shortFile = assertThrows(
                ApplicationException.class,
                () -> service.extractText(new PdfTextExtractionRequest(shortPdf.toString(), null, null))
        );
        assertEquals("file must be a PDF document", shortFile.details().get("reason"));
    }

    @Test
    void rejectsNullStoredPdfBytes() {
        assertThrows(NullPointerException.class, () -> service.extractText(null, "stored.pdf", null, false));
    }

    @Test
    void persistedRequestViewIncludesPagesByDefaultAndHonorsExplicitFalse() {
        PdfTextExtractionResult canonical = new PdfTextExtractionResult(
                "stored.pdf",
                2,
                14,
                false,
                "abcdef\n\nghijkl",
                List.of(new ExtractedPdfPage(1, "abcdef"), new ExtractedPdfPage(2, "ghijkl"))
        );

        PdfTextExtractionResult defaultView = PdfTextExtractionService.applyRequestView(canonical, 5, null);
        PdfTextExtractionResult explicitView = PdfTextExtractionService.applyRequestView(canonical, 5, true);
        PdfTextExtractionResult omittedView = PdfTextExtractionService.applyRequestView(canonical, 5, false);

        assertEquals("abcde", defaultView.text());
        assertEquals(List.of(new ExtractedPdfPage(1, "abcde")), defaultView.pages());
        assertTrue(defaultView.truncated());
        assertEquals(List.of(new ExtractedPdfPage(1, "abcde")), explicitView.pages());
        assertEquals(List.of(), omittedView.pages());
    }

    @Test
    void truncatesReturnedTextAndPages() throws IOException {
        Path pdf = createPdf("long-text.pdf", List.of("abcdef", "ghijkl"));

        PdfTextExtractionResult result = service.extractText(
                new PdfTextExtractionRequest(pdf.toString(), 5, true)
        );

        assertTrue(result.truncated());
        assertEquals(5, result.text().length());
        assertEquals("abcde", result.text());
        assertEquals(List.of(new ExtractedPdfPage(1, "abcde")), result.pages());
        assertTrue(result.characterCount() > result.text().length());
    }

    private Path createPdf(String fileName, List<String> pageTexts) throws IOException {
        Path pdf = tempDir.resolve(fileName);
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contentStream.newLineAtOffset(72, 720);
                    contentStream.showText(pageText);
                    contentStream.endText();
                }
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private static StoredDocumentFile storedFile(byte[] content, long byteSize) {
        return new StoredDocumentFile(
                UUID.randomUUID(), "stored.pdf", "application/pdf", byteSize, "sha", content,
                Instant.EPOCH, Instant.EPOCH
        );
    }

    private Path createBlankPdf(String fileName, int pageCount) throws IOException {
        Path pdf = tempDir.resolve(fileName);
        try (PDDocument document = new PDDocument()) {
            for (int pageNumber = 0; pageNumber < pageCount; pageNumber++) {
                document.addPage(new PDPage());
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private Path createOversizedPdfLikeFile(String fileName) throws IOException {
        Path pdf = tempDir.resolve(fileName);
        try (OutputStream outputStream = Files.newOutputStream(pdf)) {
            outputStream.write(new byte[]{'%', 'P', 'D', 'F', '-'});
            outputStream.write(new byte[]{'\n'});
            byte[] buffer = new byte[8192];
            long remaining = PdfTextExtractionService.MAX_FILE_BYTES + 1 - 6;
            while (remaining > 0) {
                int bytesToWrite = (int) Math.min(buffer.length, remaining);
                outputStream.write(buffer, 0, bytesToWrite);
                remaining -= bytesToWrite;
            }
        }
        return pdf;
    }

    private Path createEncryptedPdf(String fileName, String pageText) throws IOException {
        Path pdf = tempDir.resolve(fileName);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(pageText);
                contentStream.endText();
            }
            StandardProtectionPolicy protectionPolicy = new StandardProtectionPolicy(
                    "owner-password",
                    "user-password",
                    new AccessPermission()
            );
            protectionPolicy.setEncryptionKeyLength(128);
            document.protect(protectionPolicy);
            document.save(pdf.toFile());
        }
        return pdf;
    }
}
