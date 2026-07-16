package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A4, one-column, ATS-safe PDF renderer dedicated to German-market resumes. */
final class GermanLebenslaufPdfRenderer {
    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float MARGIN_X = 42;
    private static final float TOP_Y = PAGE_SIZE.getHeight() - 44;
    private static final float BOTTOM_Y = 38;
    private static final float CONTENT_WIDTH = PAGE_SIZE.getWidth() - (2 * MARGIN_X);
    private static final float PAGE_CAPACITY = TOP_Y - BOTTOM_Y;
    private static final float MIN_LAST_PAGE_USAGE_RATIO = 0.40f;
    private static final float MIN_LINK_FONT_SIZE = 7;
    private static final int MAX_PAGES = 2;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final Color TEXT = new Color(28, 32, 36);
    private static final Color MUTED = new Color(91, 99, 107);
    private static final Color ACCENT = new Color(55, 71, 79);

    private final Path outputDirectory;
    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    GermanLebenslaufPdfRenderer(Path outputDirectory) {
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null")
                .toAbsolutePath()
                .normalize();
    }

    GeneratedPdfFileResult generate(String fileName, StructuredResumeContent content) {
        StructuredResumeContent safeContent = Objects.requireNonNull(content, "content must not be null");
        String safeFileName = sanitizeFileName(fileName);
        Path outputPath = outputDirectory.resolve(safeFileName).normalize();

        try {
            Files.createDirectories(outputDirectory);
            List<PageLayout> pages = paginate(blocks(safeContent));
            try (PDDocument document = new PDDocument()) {
                for (int index = 0; index < pages.size(); index++) {
                    drawPage(document, pages.get(index), index + 1, pages.size(), safeContent.language());
                }
                document.save(outputPath.toFile());
            }
            return new GeneratedPdfFileResult(
                    safeFileName,
                    outputPath.toString(),
                    Files.size(outputPath),
                    pages.size(),
                    Instant.now().toString()
            );
        } catch (IOException exception) {
            throw new ApplicationException(
                    ApplicationErrorCode.INTERNAL_ERROR,
                    "German resume PDF could not be generated",
                    Map.of(),
                    exception
            );
        }
    }

    private List<Block> blocks(StructuredResumeContent content) throws IOException {
        boolean german = ResumeVariant.LANGUAGE_DE.equals(content.language());
        List<Block> blocks = new ArrayList<>();
        blocks.add(header(content));
        if (hasText(content.summary())) {
            List<RenderLine> summary = sectionPrefix(0, german ? "PROFIL" : "PROFILE");
            summary.addAll(wrap(content.summary(), Style.BODY, 0));
            blocks.add(new Block(summary));
        }
        addExperience(blocks, content, german);
        addProjects(blocks, content, german);
        addEducation(blocks, content, german);
        addSkills(blocks, content, german);
        addLanguages(blocks, content, german);
        return List.copyOf(blocks);
    }

    private Block header(StructuredResumeContent content) throws IOException {
        List<RenderLine> lines = new ArrayList<>(wrap(content.fullName(), Style.NAME, 0));
        lines.add(divider());
        List<StructuredResumeContent.PersonalField> contacts = content.personalFields();
        for (int index = 0; index < contacts.size();) {
            String first = contactText(contacts.get(index));
            String pair = index + 1 < contacts.size()
                    ? first + "  |  " + contactText(contacts.get(index + 1))
                    : first;
            if (textWidth(pair, Style.CONTACT) <= CONTENT_WIDTH) {
                lines.add(line(pair, Style.CONTACT, 0));
                index += 2;
            } else {
                lines.addAll(contactLines(contacts.get(index)));
                index++;
            }
        }
        return new Block(lines);
    }

    private void addExperience(List<Block> blocks, StructuredResumeContent content, boolean german) throws IOException {
        List<StructuredResumeContent.ExperienceEntry> entries = content.experiences();
        for (int index = 0; index < entries.size(); index++) {
            StructuredResumeContent.ExperienceEntry entry = entries.get(index);
            List<RenderLine> lines = sectionPrefix(index, german ? "BERUFSERFAHRUNG" : "PROFESSIONAL EXPERIENCE");
            lines.addAll(wrap(entry.title() + " | " + entry.company(), Style.ENTRY, 0));
            lines.addAll(wrap(period(entry.startDate(), entry.endDate(), german)
                    + optionalLocation(entry.location()), Style.META, 0));
            for (String bullet : entry.bullets()) {
                lines.addAll(wrapBullet(bullet));
            }
            lines.add(spacer(4));
            blocks.add(new Block(lines));
        }
    }

    private void addProjects(List<Block> blocks, StructuredResumeContent content, boolean german) throws IOException {
        List<StructuredResumeContent.AdditionalEntry> entries = content.additional();
        for (int index = 0; index < entries.size(); index++) {
            StructuredResumeContent.AdditionalEntry entry = entries.get(index);
            List<RenderLine> lines = sectionPrefix(index, german ? "PROJEKTE" : "PROJECTS");
            lines.addAll(wrap(entry.title(), Style.ENTRY, 0));
            if (hasText(entry.organization())) {
                lines.add(urlLine(entry.organization()));
            }
            for (String bullet : entry.bullets()) {
                lines.addAll(wrapBullet(bullet));
            }
            lines.add(spacer(4));
            blocks.add(new Block(lines));
        }
    }

    private void addEducation(List<Block> blocks, StructuredResumeContent content, boolean german) throws IOException {
        List<StructuredResumeContent.EducationEntry> entries = content.education();
        for (int index = 0; index < entries.size(); index++) {
            StructuredResumeContent.EducationEntry entry = entries.get(index);
            List<RenderLine> lines = sectionPrefix(index, german ? "AUSBILDUNG" : "EDUCATION");
            lines.addAll(wrap(entry.degree() + " | " + entry.institution(), Style.ENTRY, 0));
            lines.addAll(wrap(period(entry.startDate(), entry.endDate(), german)
                    + optionalLocation(entry.location()), Style.META, 0));
            for (String bullet : entry.bullets()) {
                lines.addAll(wrapBullet(bullet));
            }
            lines.add(spacer(4));
            blocks.add(new Block(lines));
        }
    }

    private void addSkills(List<Block> blocks, StructuredResumeContent content, boolean german) throws IOException {
        if (content.skillGroups().isEmpty()) {
            return;
        }
        List<RenderLine> lines = sectionPrefix(0, german ? "KENNTNISSE" : "SKILLS");
        for (StructuredResumeContent.SkillGroup group : content.skillGroups()) {
            lines.addAll(wrap(group.category() + ": " + String.join(", ", group.skills()), Style.BODY, 0));
        }
        lines.add(spacer(4));
        blocks.add(new Block(lines));
    }

    private void addLanguages(List<Block> blocks, StructuredResumeContent content, boolean german) throws IOException {
        if (content.languages().isEmpty()) {
            return;
        }
        List<RenderLine> lines = sectionPrefix(0, german ? "SPRACHEN" : "LANGUAGES");
        String text = content.languages().stream()
                .map(language -> hasText(language.proficiency())
                        ? language.language() + " (" + language.proficiency() + ")"
                        : language.language())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        lines.addAll(wrap(text, Style.BODY, 0));
        blocks.add(new Block(lines));
    }

    private List<RenderLine> sectionPrefix(int entryIndex, String title) throws IOException {
        if (entryIndex != 0) {
            return new ArrayList<>();
        }
        List<RenderLine> lines = new ArrayList<>();
        lines.add(spacer(4));
        lines.add(line(title, Style.SECTION, 0));
        return lines;
    }


    private List<PageLayout> paginate(List<Block> blocks) {
        List<PageLayout> pages = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        float used = 0;
        for (Block block : blocks) {
            if (block.height() > PAGE_CAPACITY) {
                throw invalid("content", "contains a resume block taller than one A4 page");
            }
            if (!current.isEmpty() && used + block.height() > PAGE_CAPACITY) {
                pages.add(new PageLayout(new ArrayList<>(current), used));
                current.clear();
                used = 0;
            }
            current.add(block);
            used += block.height();
        }
        pages.add(new PageLayout(new ArrayList<>(current), used));
        if (pages.size() > MAX_PAGES) {
            throw invalid("content", "must fit within two A4 resume pages");
        }
        rebalanceTrailingPage(pages);
        return List.copyOf(pages);
    }

    private static void rebalanceTrailingPage(List<PageLayout> pages) {
        if (pages.size() != 2) {
            return;
        }
        PageLayout first = pages.get(0);
        PageLayout last = pages.get(1);
        float minimum = PAGE_CAPACITY * MIN_LAST_PAGE_USAGE_RATIO;
        while (last.usedHeight() < minimum) {
            Block candidate = first.blocks().getLast();
            if (last.usedHeight() + candidate.height() > PAGE_CAPACITY
                    || first.usedHeight() - candidate.height() < minimum) {
                break;
            }
            first.blocks().removeLast();
            last.blocks().addFirst(candidate);
            first = new PageLayout(first.blocks(), first.usedHeight() - candidate.height());
            last = new PageLayout(last.blocks(), last.usedHeight() + candidate.height());
            pages.set(0, first);
            pages.set(1, last);
        }
        if (last.usedHeight() < minimum) {
            throw invalid("content", "would create a mostly empty trailing resume page");
        }
    }

    private void drawPage(
            PDDocument document,
            PageLayout layout,
            int pageNumber,
            int pageCount,
            String language
    ) throws IOException {
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
                    if (line.style() == Style.SECTION || line.style() == Style.DIVIDER) {
                        stream.setStrokingColor(ACCENT);
                        stream.setLineWidth(0.6f);
                        float ruleY = line.style() == Style.SECTION ? y + 11 : y + 6;
                        stream.moveTo(MARGIN_X, ruleY);
                        stream.lineTo(PAGE_SIZE.getWidth() - MARGIN_X, ruleY);
                        stream.stroke();
                    }
                    if (line.style() == Style.DIVIDER) {
                        continue;
                    }
                    if (line.style() == Style.BULLET) {
                        drawText(stream, regular, 9, TEXT, MARGIN_X + 2, y, "•");
                    }
                    drawText(
                            stream,
                            line.style().bold ? bold : regular,
                            line.fontSize(),
                            line.style().color,
                            MARGIN_X + line.indent(),
                            y,
                            line.text()
                    );
                }
            }
            String footer = ResumeVariant.LANGUAGE_DE.equals(language)
                    ? "Seite " + pageNumber + " / " + pageCount
                    : pageNumber + " / " + pageCount;
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
                        String fragment = fragments.get(index);
                        if (index + 1 < fragments.size()) {
                            lines.add(line(fragment, style, indent));
                        } else {
                            current.append(fragment);
                        }
                    }
                }
            }
        }
        lines.add(line(current.toString(), style, indent));
        return lines;
    }

    private List<RenderLine> wrapBullet(String text) throws IOException {
        List<RenderLine> wrapped = wrap(text, Style.BULLET, 12);
        List<RenderLine> lines = new ArrayList<>(wrapped.size());
        lines.add(wrapped.getFirst());
        wrapped.stream()
                .skip(1)
                .map(line -> new RenderLine(line.text(), Style.BODY, line.indent(), line.height(), Style.BODY.fontSize))
                .forEach(lines::add);
        return lines;
    }

    private RenderLine urlLine(String url) throws IOException {
        return fittedLinkLine(url, Style.LINK, "projectUrl");
    }

    private List<RenderLine> contactLines(StructuredResumeContent.PersonalField field) throws IOException {
        String text = contactText(field);
        String value = field.value().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://")
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
            char character = token.charAt(index);
            String candidate = current.toString() + character;
            if (!current.isEmpty() && textWidth(candidate, style) > available) {
                fragments.add(current.toString());
                current = new StringBuilder();
            }
            current.append(character);
        }
        fragments.add(current.toString());
        return fragments;
    }

    private float textWidth(String text, Style style) throws IOException {
        String safe = WinAnsiPdfText.sanitize(text);
        return (style.bold ? bold : regular).getStringWidth(safe) / 1000f * style.fontSize;
    }

    private static RenderLine line(String text, Style style, float indent) {
        return new RenderLine(WinAnsiPdfText.sanitize(text), style, indent, style.leading, style.fontSize); }
    private static RenderLine spacer(float height) { return new RenderLine("", Style.SPACER, 0, height, 0); }
    private static RenderLine divider() { return new RenderLine("", Style.DIVIDER, 0, Style.DIVIDER.leading, 0); }

    private static String contactText(StructuredResumeContent.PersonalField field) {
        return field.label() + ": " + field.value(); }
    private static String optionalLocation(String location) { return hasText(location) ? " | " + location : ""; }

    private static String period(LocalDate start, LocalDate end, boolean german) {
        String unknown = german ? "unbekannt" : "Unknown";
        String present = german ? "heute" : "Present";
        if (start == null && end == null) {
            return unknown;
        }
        String startText = start == null ? unknown : MONTH.format(start);
        String endText = end == null ? present : MONTH.format(end);
        return startText + " - " + endText;
    }

    private static boolean hasText(String value) { return value != null; }

    private static String sanitizeFileName(String value) {
        String name = Objects.toString(value, "resume.pdf").strip();
        try {
            name = Path.of(name).getFileName().toString();
        } catch (RuntimeException exception) {
            throw invalid("fileName", "must be a valid file name");
        }
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.toLowerCase(Locale.ROOT).endsWith(".pdf") ? sanitized : sanitized + ".pdf";
    }

    private static void drawText(
            PDPageContentStream stream,
            PDFont font,
            float size,
            Color color,
            float x,
            float y,
            String text
    ) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(WinAnsiPdfText.sanitize(text));
        stream.endText();
    }

    private static void drawRightAligned(
            PDPageContentStream stream,
            PDFont font,
            float size,
            Color color,
            float right,
            float y,
            String text
    ) throws IOException {
        float width = font.getStringWidth(WinAnsiPdfText.sanitize(text)) / 1000f * size;
        drawText(stream, font, size, color, right - width, y, text);
    }

    private static ApplicationException invalid(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid German resume layout",
                Map.of("field", field, "reason", reason),
                null
        );
    }
    private enum Style {
        NAME(22, 25, true, TEXT),
        CONTACT(8.5f, 10, false, MUTED),
        DIVIDER(0, 12, false, ACCENT),
        SECTION(10, 15, true, ACCENT),
        ENTRY(9.5f, 11, true, TEXT),
        META(8.5f, 10, false, MUTED),
        BODY(8.7f, 10.2f, false, TEXT),
        BULLET(8.7f, 10.2f, false, TEXT),
        LINK(8.2f, 9.5f, false, MUTED),
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
