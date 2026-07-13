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

import java.awt.Color;
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
    private static final float MARGIN = 54;
    private static final float TITLE_FONT_SIZE = 20;
    private static final float SECTION_FONT_SIZE = 10;
    private static final float BODY_FONT_SIZE = 9.5f;
    private static final float LEADING = 13.5f;
    private static final int BODY_WRAP_CHARACTERS = 104;
    private static final float HEADER_HEIGHT = 30;
    private static final float FOOTER_HEIGHT = 24;
    private static final float CHROME_FONT_SIZE = 9;
    private static final String BULLET_MARKER = "•";
    private static final String BULLET_CONTINUATION_PREFIX = "  ";
    private static final float BULLET_MARKER_INDENT = 12;
    private static final float BULLET_TEXT_INDENT = 24;
    private static final Color PAGE_BACKGROUND_COLOR = Color.WHITE;
    private static final Color CHROME_BACKGROUND_COLOR = new Color(64, 64, 64);
    private static final Color SECTION_COLOR = CHROME_BACKGROUND_COLOR;
    private static final Color TEXT_COLOR = new Color(14, 14, 14);
    private static final Color TITLE_COLOR = new Color(14, 14, 14);
    private static final Color CHROME_TEXT_COLOR = Color.WHITE;
    private static final float SECTION_SEPARATOR_WIDTH = 0.5f;

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
                if (fileNamePath != null) {
                    baseName = fileNamePath.toString();
                }
            } catch (InvalidPathException exception) {
                throw validation("fileName", "must be a valid file name");
            }
        }
        String sanitized = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.equals(".") || sanitized.equals("..")) {
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
            PDType1Font chromeFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            List<PageLines> pages = paginate(lines);

            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                PDPage page = addPage(document);
                writePage(document, page, pages.get(pageIndex), pageIndex + 1, pages.size(), titleFont, bodyFont, chromeFont);
            }
            document.save(outputPath.toFile());
            return document.getNumberOfPages();
        }
    }

    private static PDPage addPage(PDDocument document) {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        return page;
    }

    private static List<PageLines> paginate(List<String> lines) {
        int linesPerPage = Math.max(1, (int) Math.floor((bodyStartY() - bodyEndY()) / LEADING) + 1);
        List<PageLines> pages = new ArrayList<>();
        List<String> pageLines = new ArrayList<>();
        int pageStartIndex = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            if (shouldStartNewPage(lines, lineIndex, pageLines.size(), linesPerPage)) {
                pages.add(new PageLines(List.copyOf(pageLines), pageStartIndex));
                pageLines.clear();
                pageStartIndex = lineIndex;
            }
            pageLines.add(lines.get(lineIndex));
            if (pageLines.size() == linesPerPage) {
                pages.add(new PageLines(List.copyOf(pageLines), pageStartIndex));
                pageLines.clear();
                pageStartIndex = lineIndex + 1;
            }
        }
        if (!pageLines.isEmpty() || pages.isEmpty()) {
            pages.add(new PageLines(List.copyOf(pageLines), pageStartIndex));
        }
        return pages;
    }

    private static boolean shouldStartNewPage(List<String> lines, int lineIndex, int pageLineCount, int linesPerPage) {
        if (pageLineCount == 0) {
            return false;
        }
        int remainingSlots = linesPerPage - pageLineCount;
        String line = lines.get(lineIndex);
        return (isSectionHeading(line) && remainingSlots < 2)
                || (isResumeEntryHeading(lines, lineIndex) && remainingSlots < 3);
    }

    private static boolean isResumeEntryHeading(List<String> lines, int lineIndex) {
        if (lineIndex + 1 >= lines.size()) {
            return false;
        }
        String line = lines.get(lineIndex);
        String nextLine = lines.get(lineIndex + 1);
        return line != null
                && line.contains(" | ")
                && !isLabeledLine(line)
                && !isSectionHeading(line)
                && isResumeDateLine(nextLine);
    }

    private static boolean isResumeDateLine(String line) {
            return line != null && (line.startsWith("Dates not provided")
                    || line.startsWith("Unknown - ")
                    || line.startsWith("unbekannt - ")
                    || line.matches("^\\d{2}/\\d{4} - .+")
                    || line.matches("^\\d{4}-\\d{2} - .+"));
        }

    private static void writePage(
            PDDocument document,
            PDPage page,
            PageLines pageLines,
            int pageNumber,
            int pageCount,
            PDType1Font titleFont,
            PDType1Font bodyFont,
            PDType1Font chromeFont
    ) throws IOException {
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            drawBackground(contentStream, page);
            drawChrome(contentStream, page, chromeFont, pageNumber, pageCount);
            drawBody(contentStream, pageLines, titleFont, bodyFont, chromeFont);
        }
    }

    private static void drawBackground(PDPageContentStream contentStream, PDPage page) throws IOException {
        PDRectangle box = page.getMediaBox();
        contentStream.setNonStrokingColor(PAGE_BACKGROUND_COLOR);
        contentStream.addRect(0, 0, box.getWidth(), box.getHeight());
        contentStream.fill();
    }

    private static void drawChrome(
            PDPageContentStream contentStream,
            PDPage page,
            PDType1Font chromeFont,
            int pageNumber,
            int pageCount
    ) throws IOException {
        PDRectangle box = page.getMediaBox();
        String chromeText = "Page " + pageNumber + " of " + pageCount;

        contentStream.setNonStrokingColor(CHROME_BACKGROUND_COLOR);
        contentStream.addRect(0, box.getHeight() - HEADER_HEIGHT, box.getWidth(), HEADER_HEIGHT);
        contentStream.fill();
        contentStream.addRect(0, 0, box.getWidth(), FOOTER_HEIGHT);
        contentStream.fill();

        drawRightAlignedText(contentStream, chromeFont, CHROME_FONT_SIZE, CHROME_TEXT_COLOR,
                box.getWidth() - MARGIN, box.getHeight() - 19, chromeText);
        drawRightAlignedText(contentStream, chromeFont, CHROME_FONT_SIZE, CHROME_TEXT_COLOR,
                box.getWidth() - MARGIN, 10, chromeText);
    }

    private static void drawBody(PDPageContentStream contentStream, PageLines pageLines, PDType1Font titleFont, PDType1Font bodyFont, PDType1Font chromeFont) throws IOException {
        for (int index = 0; index < pageLines.lines().size(); index++) {
            int globalIndex = pageLines.startIndex() + index;
            float y = bodyStartY() - (index * LEADING);
            String line = pageLines.lines().get(index);
            if (globalIndex == 0) {
                drawText(contentStream, titleFont, TITLE_FONT_SIZE, TITLE_COLOR, MARGIN, y, line);
            } else {
                if (isBulletContinuationLine(line)) {
                    drawText(contentStream, bodyFont, BODY_FONT_SIZE, TEXT_COLOR, MARGIN + BULLET_TEXT_INDENT, y, line.stripLeading());
                } else if (isSectionHeading(line)) {
                    drawSectionSeparator(contentStream, y + 9);
                    drawText(contentStream, chromeFont, SECTION_FONT_SIZE, SECTION_COLOR, MARGIN, y, line);
                } else if (isBulletLine(line)) {
                    drawBulletLine(contentStream, bodyFont, y, line);
                } else if (isLabeledLine(line)) {
                    drawLabeledLine(contentStream, chromeFont, bodyFont, y, line);
                } else if (!line.isEmpty()) {
                    drawText(contentStream, bodyFont, BODY_FONT_SIZE, TEXT_COLOR, MARGIN, y, line);
                }
            }
        }
    }

    private static boolean isSectionHeading(String line) {
        if (line == null || line.isBlank() || line.length() > 48 || line.contains(":")) {
            return false;
        }
        String stripped = line.strip();
        return stripped.chars().anyMatch(Character::isLetter)
                && stripped.equals(stripped.toUpperCase(java.util.Locale.ROOT));
    }

    private static boolean isBulletLine(String line) {
        return line != null && line.stripLeading().startsWith("-");
    }

    private static boolean isBulletContinuationLine(String line) {
        return line != null && line.startsWith(BULLET_CONTINUATION_PREFIX) && !line.isBlank();
    }

    private static void drawBulletLine(PDPageContentStream contentStream, PDType1Font bodyFont, float y, String line) throws IOException {
        drawText(contentStream, bodyFont, BODY_FONT_SIZE, TEXT_COLOR, MARGIN + BULLET_MARKER_INDENT, y, BULLET_MARKER);
        drawText(contentStream, bodyFont, BODY_FONT_SIZE, TEXT_COLOR, MARGIN + BULLET_TEXT_INDENT, y, stripBulletPrefix(line));
    }

    private static boolean isLabeledLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        int colonIndex = line.indexOf(':');
        return colonIndex > 0 && colonIndex <= 32;
    }

    private static void drawLabeledLine(PDPageContentStream contentStream, PDType1Font labelFont, PDType1Font bodyFont, float y, String line) throws IOException {
        int colonIndex = line.indexOf(':');
        String label = line.substring(0, colonIndex + 1);
        String value = line.substring(colonIndex + 1).stripLeading();
        drawText(contentStream, labelFont, BODY_FONT_SIZE, TEXT_COLOR, MARGIN, y, label);
        float labelWidth = labelFont.getStringWidth(label + " ") / 1000 * BODY_FONT_SIZE;
        drawText(contentStream, bodyFont, BODY_FONT_SIZE, TEXT_COLOR, MARGIN + labelWidth, y, value);
    }

    private static void drawSectionSeparator(PDPageContentStream contentStream, float y) throws IOException {
        contentStream.setStrokingColor(CHROME_BACKGROUND_COLOR);
        contentStream.setLineWidth(SECTION_SEPARATOR_WIDTH);
        contentStream.moveTo(MARGIN, y);
        contentStream.lineTo(PDRectangle.LETTER.getWidth() - MARGIN, y);
        contentStream.stroke();
    }

    private static void drawText(PDPageContentStream contentStream, PDType1Font font, float fontSize, Color color, float x, float y, String text) throws IOException {
        contentStream.beginText();
        contentStream.setFont(font, fontSize);
        contentStream.setNonStrokingColor(color);
        contentStream.newLineAtOffset(x, y);
        contentStream.showText(text);
        contentStream.endText();
    }

    private static void drawRightAlignedText(
            PDPageContentStream contentStream,
            PDType1Font font,
            float fontSize,
            Color color,
            float rightX,
            float y,
            String text
    ) throws IOException {
        float width = font.getStringWidth(text) / 1000 * fontSize;
        drawText(contentStream, font, fontSize, color, rightX - width, y, text);
    }

    private static String fitText(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        if (font.getStringWidth(text) / 1000 * fontSize <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        String remaining = text;
        while (!remaining.isEmpty() && font.getStringWidth(remaining + ellipsis) / 1000 * fontSize > maxWidth) {
            remaining = remaining.substring(0, remaining.length() - 1).stripTrailing();
        }
        return remaining.isEmpty() ? ellipsis : remaining + ellipsis;
    }

    private static float bodyStartY() {
        return PDRectangle.LETTER.getHeight() - HEADER_HEIGHT - 36;
    }

    private static float bodyEndY() {
        return FOOTER_HEIGHT + 36;
    }

    private static List<String> bodyLines(String title, String body) {
        List<String> lines = new ArrayList<>();
        lines.add(title);
        lines.add("");
        for (String paragraph : body.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
            } else {
                String sanitizedParagraph = sanitizeText(paragraph.strip());
                if (isBulletLine(sanitizedParagraph)) {
                    lines.addAll(wrapBullet(sanitizedParagraph, BODY_WRAP_CHARACTERS));
                } else if (isLabeledLine(sanitizedParagraph)) {
                    lines.addAll(wrapLabeledLine(sanitizedParagraph, BODY_WRAP_CHARACTERS));
                } else {
                    lines.addAll(wrap(sanitizedParagraph, BODY_WRAP_CHARACTERS));
                }
            }
        }
        return lines;
    }

    private static List<String> wrapBullet(String text, int maxCharacters) {
        List<String> wrappedText = wrap(stripBulletPrefix(text), Math.max(1, maxCharacters - 4));
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < wrappedText.size(); index++) {
            if (index == 0) {
                lines.add("- " + wrappedText.get(index));
            } else {
                lines.add(BULLET_CONTINUATION_PREFIX + wrappedText.get(index));
            }
        }
        return lines;
    }

    private static List<String> wrapLabeledLine(String text, int maxCharacters) {
        int colonIndex = text.indexOf(':');
        String label = text.substring(0, colonIndex + 1);
        String value = text.substring(colonIndex + 1).stripLeading();
        List<String> wrappedText = wrap(value, Math.max(1, maxCharacters - label.length() - 1));
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < wrappedText.size(); index++) {
            if (index == 0) {
                lines.add(label + " " + wrappedText.get(index));
            } else {
                lines.add(BULLET_CONTINUATION_PREFIX + wrappedText.get(index));
            }
        }
        return lines;
    }

    private static String stripBulletPrefix(String line) {
        String stripped = line.stripLeading();
        return stripped.startsWith("-") ? stripped.substring(1).stripLeading() : stripped;
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

    private record PageLines(List<String> lines, int startIndex) {
    }
}
