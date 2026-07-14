package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class PdfTextExtractionService {

    public static final int DEFAULT_MAX_CHARACTERS = 100_000;
    public static final int MAX_CHARACTERS_LIMIT = 250_000;
    public static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    public static final int MAX_PAGE_COUNT = 200;
    private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};
    private final LocalFileImportPolicy importPolicy;

    public PdfTextExtractionService() {
        this.importPolicy = LocalFileImportPolicy.unrestrictedForTests();
    }

    @Autowired
    public PdfTextExtractionService(
            @Value("${job-engine.document.import-root:" + LocalFileImportPolicy.DEFAULT_IMPORT_ROOT + "}") String importRoot
    ) {
        this.importPolicy = LocalFileImportPolicy.rootedAt(importRoot);
    }

    public PdfTextExtractionResult extractText(PdfTextExtractionRequest request) {
        PdfTextExtractionRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        Path pdfPath = validatePath(safeRequest.path());
        PdfContentSnapshot snapshot = readLocalPdfSnapshot(pdfPath);
        return extractText(snapshot, safeRequest.maxCharacters(), safeRequest.includePages());
    }

    public PdfTextExtractionResult extractText(byte[] pdfContent, String fileName, Integer maxCharacters, Boolean includePages) {
        Objects.requireNonNull(pdfContent, "pdfContent must not be null");
        String safeFileName = fileName == null || fileName.isBlank() ? "stored.pdf" : Path.of(fileName).getFileName().toString();
        PdfContentSnapshot snapshot = validatedSnapshot(pdfContent, safeFileName);
        return extractText(snapshot, safeFileName, maxCharacters, includePages);
    }

    public PdfTextExtractionResult extractText(StoredDocumentFile pdfFile, Integer maxCharacters, Boolean includePages) {
        StoredDocumentFile safeFile = Objects.requireNonNull(pdfFile, "pdfFile must not be null");
        validateFileSize(safeFile.contentLength());
        if (!hasPdfHeader(safeFile.openContentStream())) {
            throw validation("path", "file must be a PDF document");
        }
        return extractText(
                new StoredDocumentResource(safeFile),
                safeFile.originalFileName(),
                maxCharacters,
                includePages
        );
    }

    PdfTextExtractionResult extractText(PdfContentSnapshot snapshot, Integer maxCharacters, Boolean includePages) {
        PdfContentSnapshot safeSnapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        return extractText(safeSnapshot, safeSnapshot.getFilename(), maxCharacters, includePages);
    }

    static PdfTextExtractionResult applyRequestView(
            PdfTextExtractionResult canonical,
            Integer maxCharactersValue,
            Boolean includePagesValue
    ) {
        Objects.requireNonNull(canonical, "canonical must not be null");
        int maxCharacters = resolveMaxCharacters(maxCharactersValue);
        boolean includePages = includePagesValue == null || includePagesValue;
        return new PdfTextExtractionResult(
                canonical.fileName(),
                canonical.pageCount(),
                canonical.characterCount(),
                canonical.characterCount() > maxCharacters,
                truncate(canonical.text(), maxCharacters),
                includePages ? truncatePages(canonical.pages(), maxCharacters) : List.of()
        );
    }

    private PdfTextExtractionResult extractText(Resource pdfResource, String fileName, Integer maxCharactersValue, Boolean includePagesValue) {
        int maxCharacters = resolveMaxCharacters(maxCharactersValue);
        boolean includePages = includePagesValue == null || includePagesValue;

        List<Document> documents = readDocuments(pdfResource);
        List<ExtractedPdfPage> extractedPages = toPages(documents);
        String fullText = joinPageText(extractedPages);
        String truncatedText = truncate(fullText, maxCharacters);
        List<ExtractedPdfPage> responsePages = includePages
                ? truncatePages(extractedPages, maxCharacters)
                : List.of();

        return new PdfTextExtractionResult(
                fileName,
                documents.size(),
                fullText.length(),
                fullText.length() > maxCharacters,
                truncatedText,
                responsePages
        );
    }

    private Path validatePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw validation("path", "must not be blank");
        }

        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw validation("path", "must be a valid file path");
        }

        if (!Files.exists(path)) {
            throw validation("path", "file was not found");
        }
        if (!Files.isRegularFile(path)) {
            throw validation("path", "must identify a regular file");
        }
        Path allowedPath = importPolicy.requireAllowed(path);
        return allowedPath;
    }

    private static void validateFileSize(long byteSize) {
        if (byteSize > MAX_FILE_BYTES) {
            throw validation("path", "PDF file size exceeds limit of " + MAX_FILE_BYTES + " bytes");
        }
    }

    static boolean hasPdfHeader(InputStream content) {
        Objects.requireNonNull(content, "content must not be null");
        try {
            return hasPdfHeader(content.readNBytes(PDF_MAGIC.length));
        } catch (IOException exception) {
            throw validation("path", "file is not readable");
        }
    }

    private static boolean hasPdfHeader(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            return false;
        }
        for (int index = 0; index < PDF_MAGIC.length; index++) {
            if (content[index] != PDF_MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    private static int resolveMaxCharacters(Integer maxCharacters) {
        if (maxCharacters == null) {
            return DEFAULT_MAX_CHARACTERS;
        }
        if (maxCharacters <= 0) {
            throw validation("maxCharacters", "must be greater than zero");
        }
        if (maxCharacters > MAX_CHARACTERS_LIMIT) {
            throw validation("maxCharacters", "must be less than or equal to " + MAX_CHARACTERS_LIMIT);
        }
        return maxCharacters;
    }

    private static List<Document> readDocuments(Resource pdfResource) {
        try {
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                            .withLeftAlignment(true)
                            .build())
                    .build();
            try (CloseablePagePdfDocumentReader reader = new CloseablePagePdfDocumentReader(
                    pdfResource,
                    config
            )) {
                if (reader.pageCount() > MAX_PAGE_COUNT) {
                    throw validation("path", "PDF page count exceeds limit of " + MAX_PAGE_COUNT);
                }
                return reader.get();
            }
        } catch (ApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "PDF text could not be extracted",
                    Map.of("field", "path", "reason", "PDF text could not be extracted"),
                    exception
            );
        }
    }

    private static List<ExtractedPdfPage> toPages(List<Document> documents) {
        List<ExtractedPdfPage> pages = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            pages.add(new ExtractedPdfPage(pageNumber(document, index), safeText(document.getText())));
        }
        return List.copyOf(pages);
    }

    private static int pageNumber(Document document, int index) {
        return Optional.ofNullable(document.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .orElse(index + 1);
    }

    private static String joinPageText(List<ExtractedPdfPage> pages) {
        return pages.stream()
                .map(ExtractedPdfPage::text)
                .collect(Collectors.joining("\n\n"));
    }

    private static String safeText(String text) {
        return Objects.requireNonNullElse(text, "").strip().replaceAll("\\s+", " ");
    }

    private static String truncate(String text, int maxCharacters) {
        if (text.length() <= maxCharacters) {
            return text;
        }
        return text.substring(0, maxCharacters);
    }

    private static List<ExtractedPdfPage> truncatePages(List<ExtractedPdfPage> pages, int maxCharacters) {
        List<ExtractedPdfPage> truncatedPages = new ArrayList<>();
        int remainingCharacters = maxCharacters;
        for (ExtractedPdfPage page : pages) {
            if (remainingCharacters <= 0) {
                break;
            }
            String pageText = truncate(page.text(), remainingCharacters);
            truncatedPages.add(new ExtractedPdfPage(page.pageNumber(), pageText));
            remainingCharacters -= pageText.length();
        }
        return List.copyOf(truncatedPages);
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid PDF text extraction request",
                Map.of("field", field, "reason", reason),
                null
        );
    }

    private static void closeDocument(PDDocument document) {
        try {
            document.close();
        } catch (IOException exception) {
            throw new IllegalStateException("PDF document could not be closed", exception);
        }
    }

    public record PdfTextExtractionRequest(String path, Integer maxCharacters, Boolean includePages) {
    }

    public record PdfTextExtractionResult(
            String fileName,
            int pageCount,
            int characterCount,
            boolean truncated,
            String text,
            List<ExtractedPdfPage> pages
    ) {
    }

    public record ExtractedPdfPage(int pageNumber, String text) {
    }

    private static final class CloseablePagePdfDocumentReader extends PagePdfDocumentReader implements AutoCloseable {

        private CloseablePagePdfDocumentReader(Resource resource, PdfDocumentReaderConfig config) {
            super(resource, config);
        }

        @Override
        public void close() {
            closeDocument(document);
        }

        private int pageCount() {
            return document.getNumberOfPages();
        }
    }

    static PdfContentSnapshot readLocalPdfSnapshot(Path path) {
        byte[] content = readBoundedContent(path);
        return validatedSnapshot(content, path.getFileName().toString());
    }

    static byte[] readBoundedContent(Path path) {
        try (InputStream input = Files.newInputStream(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            return input.readNBytes(Math.toIntExact(MAX_FILE_BYTES + 1));
        } catch (IOException exception) {
            throw validation("path", "file is not readable");
        }
    }

    private static PdfContentSnapshot validatedSnapshot(byte[] content, String fileName) {
        validateFileSize(content.length);
        if (!hasPdfHeader(content)) {
            throw validation("path", "file must be a PDF document");
        }
        return new PdfContentSnapshot(content, fileName);
    }

    static final class PdfContentSnapshot extends AbstractResource {

        private final byte[] content;
        private final String filename;

        private PdfContentSnapshot(byte[] byteArray, String filename) {
            this.content = Arrays.copyOf(byteArray, byteArray.length);
            this.filename = filename;
        }

        @Override
        public String getDescription() {
            return filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return content.length;
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(content);
        }
    }

    static final class StoredDocumentResource extends AbstractResource {

        private final StoredDocumentFile file;

        StoredDocumentResource(StoredDocumentFile file) {
            this.file = file;
        }

        @Override
        public String getDescription() {
            return file.originalFileName();
        }

        @Override
        public String getFilename() {
            return file.originalFileName();
        }

        @Override
        public long contentLength() {
            return file.contentLength();
        }

        @Override
        public InputStream getInputStream() {
            return file.openContentStream();
        }
    }
}
