package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.instruct.jobenginespring.application.coverletter.GermanCoverLetterContent;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A4 selectable-text renderer for the Germany-format cover-letter variant. */
final class GermanCoverLetterPdfRenderer {

    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float MARGIN_X = 42;
    private static final float TOP_Y = PAGE_SIZE.getHeight() - 44;
    private static final float BOTTOM_Y = 38;
    private static final float CONTENT_WIDTH = PAGE_SIZE.getWidth() - (2 * MARGIN_X);
    private static final float PAGE_CAPACITY = TOP_Y - BOTTOM_Y;
    private static final int MAX_PAGES = 2;
    private static final float MIN_LINK_FONT_SIZE = 7;
    private static final Color TEXT = new Color(28, 32, 36);
    private static final Color MUTED = new Color(91, 99, 107);
    private static final Color ACCENT = new Color(55, 71, 79);

    private final Path outputDirectory;
    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    GermanCoverLetterPdfRenderer(Path outputDirectory) {
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null")
                .toAbsolutePath()
                .normalize();
    }

    PdfGenerationService.GeneratedPdfFileResult generate(String fileName, GermanCoverLetterContent content) {
        GermanCoverLetterContent safeContent = Objects.requireNonNull(content, "content must not be null");
        String safeFileName = sanitizeFileName(fileName);
        Path outputPath = outputDirectory.resolve(safeFileName).normalize();
        try {
            Files.createDirectories(outputDirectory);
            List<PageLayout> pages = paginate(blocks(safeContent));
            try (PDDocument document = new PDDocument()) {
                for (int index = 0; index < pages.size(); index++) {
                    drawPage(document, pages.get(index), index + 1, pages.size());
                }
                document.save(outputPath.toFile());
            }
            return new PdfGenerationService.GeneratedPdfFileResult(
                    safeFileName,
                    outputPath.toString(),
                    Files.size(outputPath),
                    pages.size(),
                    Instant.now().toString()
            );
        } catch (IOException exception) {
            throw new ApplicationException(
                    ApplicationErrorCode.INTERNAL_ERROR,
                    "German cover-letter PDF could not be generated",
                    Map.of(),
                    exception
            );
        }
    }

    private List<Block> blocks(GermanCoverLetterContent content) throws IOException {
        List<Block> blocks = new ArrayList<>();
        blocks.add(header(content));

        List<RenderLine> recipient = new ArrayList<>();
        recipient.add(spacer(8));
        if (hasText(content.recipientCompany())) {
            recipient.addAll(wrap(content.recipientCompany(), Style.RECIPIENT, 0));
        }
        if (hasText(content.recipientLocation())) {
            recipient.addAll(wrap(content.recipientLocation(), Style.META, 0));
        }
        if (recipient.size() > 1) {
            blocks.add(new Block(recipient));
        }

        List<RenderLine> subject = new ArrayList<>();
        subject.add(spacer(12));
        subject.add(line(content.subject(), Style.SUBJECT, 0));
        subject.add(divider());
        blocks.add(new Block(subject));

        List<RenderLine> salutation = new ArrayList<>();
        salutation.add(spacer(10));
        salutation.addAll(wrap(content.salutation(), Style.BODY, 0));
        blocks.add(new Block(salutation));

        for (String paragraph : content.paragraphs()) {
            List<RenderLine> lines = new ArrayList<>();
            lines.add(spacer(8));
            lines.addAll(wrap(paragraph, Style.BODY, 0));
            blocks.add(new Block(lines));
        }

        List<RenderLine> closing = new ArrayList<>();
        closing.add(spacer(12));
        closing.addAll(wrap(content.closing(), Style.BODY, 0));
        closing.add(spacer(12));
        closing.addAll(wrap(content.signature(), Style.SIGNATURE, 0));
        blocks.add(new Block(closing));
        return List.copyOf(blocks);
    }

    private Block header(GermanCoverLetterContent content) throws IOException {
        List<RenderLine> lines = new ArrayList<>(wrap(content.senderName(), Style.NAME, 0));
        lines.add(divider());
        for (int index = 0; index < content.personalFields().size();) {
            String first = contactText(content.personalFields().get(index));
            String pair = index + 1 < content.personalFields().size()
                    ? first + "  |  " + contactText(content.personalFields().get(index + 1))
                    : first;
            if (textWidth(pair, Style.CONTACT) <= CONTENT_WIDTH) {
                lines.add(line(pair, Style.CONTACT, 0));
                index += 2;
            } else {
                lines.addAll(contactLines(content.personalFields().get(index)));
                index++;
            }
        }
        return new Block(lines);
    }

    private List<PageLayout> paginate(List<Block> blocks) {
        List<PageLayout> pages = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        float used = 0;
        for (Block block : blocks) {
            if (block.height() > PAGE_CAPACITY) {
                throw invalid("content", "contains a cover-letter block taller than one A4 page");
            }
            if (!current.isEmpty() && used + block.height() > PAGE_CAPACITY) {
                pages.add(new PageLayout(List.copyOf(current), used));
                current.clear();
                used = 0;
            }
            current.add(block);
            used += block.height();
        }
        pages.add(new PageLayout(List.copyOf(current), used));
        if (pages.size() > MAX_PAGES) {
            throw invalid("content", "must fit within one or two A4 pages");
        }
        return List.copyOf(pages);
    }

    private void drawPage(PDDocument document, PageLayout layout, int pageNumber, int pageCount) throws IOException {
        PDPage page = new PDPage(PAGE_SIZE);
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            float y = TOP_Y;
            for (Block block : layout.blocks()) {
                for (RenderLine line : block.lines()) {
                    y -= line.height();
                    if (line.style() == Style.SPACER) {
                        continue;
                    }
                    if (line.style() == Style.DIVIDER) {
                        stream.setStrokingColor(ACCENT);
                        stream.setLineWidth(0.6f);
                        stream.moveTo(MARGIN_X, y + 6);
                        stream.lineTo(PAGE_SIZE.getWidth() - MARGIN_X, y + 6);
                        stream.stroke();
                        continue;
                    }
                    drawText(stream, line.style().bold ? bold : regular, line.fontSize(), line.style().color,
                            MARGIN_X + line.indent(), y, line.text());
                }
            }
            String footer = "Seite " + pageNumber + " / " + pageCount;
            drawRightAligned(stream, regular, 7.5f, MUTED, PAGE_SIZE.getWidth() - MARGIN_X, 20, footer);
        }
    }

    private List<RenderLine> wrap(String text, Style style, float indent) throws IOException {
        String safe = WinAnsiPdfText.sanitize(Objects.requireNonNull(text, "text must not be null")).strip();
        float available = CONTENT_WIDTH - indent;
        List<RenderLine> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : safe.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textWidth(candidate, style) <= available) {
                current = new StringBuilder(candidate);
            } else {
                if (!current.isEmpty()) {
                    lines.add(line(current.toString(), style, indent));
                    current = new StringBuilder();
                }
                if (textWidth(word, style) <= available) {
                    current.append(word);
                } else {
                    List<String> fragments = splitLongToken(word, style, available);
                    for (int index = 0; index < fragments.size(); index++) {
                        if (index + 1 < fragments.size()) {
                            lines.add(line(fragments.get(index), style, indent));
                        } else {
                            current.append(fragments.get(index));
                        }
                    }
                }
            }
        }
        if (!current.isEmpty()) {
            lines.add(line(current.toString(), style, indent));
        }
        return lines;
    }

    private List<RenderLine> contactLines(GermanCoverLetterContent.PersonalField field) throws IOException {
        String text = contactText(field);
        return isHttpUrl(field.value())
                ? List.of(fittedLinkLine(text, Style.CONTACT, "contactUrl"))
                : wrap(text, Style.CONTACT, 0);
    }

    private RenderLine fittedLinkLine(String text, Style style, String field) throws IOException {
        String safe = WinAnsiPdfText.sanitize(text);
        float width = textWidth(safe, style);
        float fontSize = width <= CONTENT_WIDTH ? style.fontSize : style.fontSize * CONTENT_WIDTH / width;
        if (fontSize < MIN_LINK_FONT_SIZE) {
            throw invalid(field, "must fit on one readable line");
        }
        return new RenderLine(safe, style, 0, style.leading, fontSize);
    }

    private List<String> splitLongToken(String token, Style style, float available) throws IOException {
        List<String> fragments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < token.length(); index++) {
            String candidate = current + String.valueOf(token.charAt(index));
            if (!current.isEmpty() && textWidth(candidate, style) > available) {
                fragments.add(current.toString());
                current = new StringBuilder();
            }
            current.append(token.charAt(index));
        }
        fragments.add(current.toString());
        return fragments;
    }

    private float textWidth(String text, Style style) throws IOException {
        String safe = WinAnsiPdfText.sanitize(text);
        return (style.bold ? bold : regular).getStringWidth(safe) / 1000f * style.fontSize;
    }

    private static RenderLine line(String text, Style style, float indent) {
        return new RenderLine(WinAnsiPdfText.sanitize(text), style, indent, style.leading, style.fontSize);
    }

    private static RenderLine spacer(float height) {
        return new RenderLine("", Style.SPACER, 0, height, 0);
    }

    private static RenderLine divider() {
        return new RenderLine("", Style.DIVIDER, 0, Style.DIVIDER.leading, 0);
    }

    private static String contactText(GermanCoverLetterContent.PersonalField field) {
        return field.label() + ": " + field.value();
    }

    private static boolean isHttpUrl(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean hasText(String value) {
        return value != null;
    }

    private static String sanitizeFileName(String value) {
        String name = Objects.toString(value, "cover-letter.pdf").strip();
        try {
            name = Path.of(name).getFileName().toString();
        } catch (RuntimeException exception) {
            throw invalid("fileName", "must be a valid file name");
        }
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.equals(".") || sanitized.equals("..")) {
            sanitized = "cover-letter.pdf";
        }
        return sanitized.toLowerCase(Locale.ROOT).endsWith(".pdf") ? sanitized : sanitized + ".pdf";
    }

    private static void drawText(PDPageContentStream stream, PDFont font, float size, Color color,
                                 float x, float y, String text) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(WinAnsiPdfText.sanitize(text));
        stream.endText();
    }

    private static void drawRightAligned(PDPageContentStream stream, PDFont font, float size, Color color,
                                         float right, float y, String text) throws IOException {
        String safe = WinAnsiPdfText.sanitize(text);
        float width = font.getStringWidth(safe) / 1000f * size;
        drawText(stream, font, size, color, right - width, y, safe);
    }

    private static ApplicationException invalid(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid German cover-letter layout",
                Map.of("field", field, "reason", reason),
                null
        );
    }

    private enum Style {
        NAME(20, 23, true, TEXT),
        CONTACT(8.5f, 10, false, MUTED),
        RECIPIENT(9.5f, 12, true, TEXT),
        META(8.5f, 10, false, MUTED),
        SUBJECT(12, 16, true, ACCENT),
        BODY(10, 14, false, TEXT),
        SIGNATURE(10, 14, true, TEXT),
        DIVIDER(0, 12, false, ACCENT),
        SPACER(0, 0, false, TEXT);

        private final float fontSize;
        private final float leading;
        private final boolean bold;
        private final Color color;

        Style(float fontSize, float leading, boolean bold, Color color) {
            this.fontSize = fontSize;
            this.leading = leading;
            this.bold = bold;
            this.color = color;
        }
    }

    private record RenderLine(String text, Style style, float indent, float height, float fontSize) {
    }

    private record Block(List<RenderLine> lines) {
        private Block {
            lines = List.copyOf(lines);
        }

        private float height() {
            return (float) lines.stream().mapToDouble(RenderLine::height).sum();
        }
    }

    private record PageLayout(List<Block> blocks, float usedHeight) {
    }
}
