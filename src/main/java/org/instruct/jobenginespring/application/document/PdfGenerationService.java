package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PdfGenerationService {

    public static final int MAX_BODY_CHARACTERS = 50_000;
    private static final String DEFAULT_FILE_NAME = "generated.pdf";
    private static final String DEFAULT_TITLE = "Generated PDF";
    private static final float MARGIN = 72;
    private static final float TITLE_FONT_SIZE = 16;
    private static final float BODY_FONT_SIZE = 11;
    private static final float LEADING = 16;
    private static final int BODY_WRAP_CHARACTERS = 86;

    private final Path outputDirectory;

    @Autowired
    public PdfGenerationService(@Value("${job-engine.pdf-generation.output-dir:tmp/generated-pdfs}") String outputDirectory) {
        this(toPath(outputDirectory));
    }

    PdfGenerationService(Path outputDirectory) {
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null")
                .toAbsolutePath()
                .normalize();
    }

    public GeneratedPdfFileResult generatePdfFile(GeneratePdfFileRequest request) {
        GeneratePdfFileRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        String body = validateBody(safeRequest.body());
        String fileName = sanitizeFileName(safeRequest.fileName());
        String title = sanitizeText(resolveTitle(safeRequest.title()));
        Path outputPath = outputDirectory.resolve(fileName).normalize();
        if (!outputPath.startsWith(outputDirectory)) {
            throw validation("fileName", "must resolve inside the generated PDF directory");
        }

        try {
            Files.createDirectories(outputDirectory);
            int pageCount = writePdf(outputPath, title, body);
            return new GeneratedPdfFileResult(
                    fileName,
                    outputPath.toString(),
                    Files.size(outputPath),
                    pageCount,
                    Instant.now().toString()
            );
        } catch (IOException exception) {
            throw new ApplicationException(
                    ApplicationErrorCode.INTERNAL_ERROR,
                    "PDF file could not be generated",
                    Map.of(),
                    exception
            );
        }
    }

    private static Path toPath(String rawPath) {
        try {
            return Path.of(rawPath == null || rawPath.isBlank() ? "tmp/generated-pdfs" : rawPath);
        } catch (InvalidPathException exception) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Invalid PDF generation configuration",
                    Map.of("field", "outputDirectory", "reason", "must be a valid file path"),
                    exception
            );
        }
    }

    private static String validateBody(String body) {
        if (body == null || body.isBlank()) {
            throw validation("body", "must not be blank");
        }
        String strippedBody = body.strip();
        if (strippedBody.length() > MAX_BODY_CHARACTERS) {
            throw validation("body", "must be less than or equal to " + MAX_BODY_CHARACTERS + " characters");
        }
        return strippedBody;
    }

    private static String resolveTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        return title.strip();
    }

    private static String sanitizeFileName(String rawFileName) {
        String baseName = DEFAULT_FILE_NAME;
        if (rawFileName != null && !rawFileName.isBlank()) {
            try {
                Path fileNamePath = Path.of(rawFileName.strip()).getFileName();
                if (fileNamePath != null && !fileNamePath.toString().isBlank()) {
                    baseName = fileNamePath.toString();
                }
            } catch (InvalidPathException exception) {
                throw validation("fileName", "must be a valid file name");
            }
        }
        String sanitized = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            sanitized = DEFAULT_FILE_NAME;
        }
        if (!sanitized.toLowerCase().endsWith(".pdf")) {
            sanitized = sanitized + ".pdf";
        }
        return sanitized;
    }

    private static int writePdf(Path outputPath, String title, String body) throws IOException {
        try (PDDocument document = new PDDocument()) {
            List<String> lines = bodyLines(title, body);
            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = addPage(document);
            PDPageContentStream contentStream = startPage(document, page);
            float y = page.getMediaBox().getHeight() - MARGIN;
            boolean titleWritten = false;

            for (String line : lines) {
                if (y < MARGIN) {
                    contentStream.endText();
                    contentStream.close();
                    page = addPage(document);
                    contentStream = startPage(document, page);
                    y = page.getMediaBox().getHeight() - MARGIN;
                }
                if (!titleWritten) {
                    contentStream.setFont(titleFont, TITLE_FONT_SIZE);
                    titleWritten = true;
                } else {
                    contentStream.setFont(bodyFont, BODY_FONT_SIZE);
                }
                contentStream.newLineAtOffset(0, y == page.getMediaBox().getHeight() - MARGIN ? 0 : -LEADING);
                contentStream.showText(line);
                y -= LEADING;
            }
            contentStream.endText();
            contentStream.close();
            document.save(outputPath.toFile());
            return document.getNumberOfPages();
        }
    }

    private static PDPage addPage(PDDocument document) {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        return page;
    }

    private static PDPageContentStream startPage(PDDocument document, PDPage page) throws IOException {
        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        contentStream.beginText();
        contentStream.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);
        return contentStream;
    }

    private static List<String> bodyLines(String title, String body) {
        List<String> lines = new ArrayList<>();
        lines.add(title);
        lines.add("");
        for (String paragraph : body.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
            } else {
                lines.addAll(wrap(sanitizeText(paragraph.strip()), BODY_WRAP_CHARACTERS));
            }
        }
        return lines;
    }

    private static List<String> wrap(String text, int maxCharacters) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > maxCharacters) {
            int breakIndex = remaining.lastIndexOf(' ', maxCharacters);
            if (breakIndex <= 0) {
                breakIndex = maxCharacters;
            }
            lines.add(remaining.substring(0, breakIndex).stripTrailing());
            remaining = remaining.substring(breakIndex).stripLeading();
        }
        lines.add(remaining);
        return lines;
    }

    private static String sanitizeText(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\t') {
                builder.append(' ');
            } else if (character >= 32 && character <= 255) {
                builder.append(character);
            } else if (Character.isWhitespace(character)) {
                builder.append(' ');
            } else {
                builder.append('?');
            }
        }
        return builder.toString();
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid PDF generation request",
                Map.of("field", field, "reason", reason),
                null
        );
    }

    public record GeneratePdfFileRequest(String fileName, String title, String body) {
    }

    public record GeneratedPdfFileResult(
            String fileName,
            String path,
            long byteSize,
            int pageCount,
            String generatedAt
    ) {
    }
}
