package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
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

    public PdfTextExtractionResult extractText(PdfTextExtractionRequest request) {
        PdfTextExtractionRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        Path pdfPath = validatePath(safeRequest.path());
        return extractText(new FileSystemResource(pdfPath), pdfPath.getFileName().toString(), safeRequest.maxCharacters(), safeRequest.includePages());
    }

    public PdfTextExtractionResult extractText(byte[] pdfContent, String fileName, Integer maxCharacters, Boolean includePages) {
        Objects.requireNonNull(pdfContent, "pdfContent must not be null");
        validateFileSize(pdfContent.length);
        if (!hasPdfHeader(pdfContent)) {
            throw validation("path", "file must be a PDF document");
        }
        String safeFileName = fileName == null || fileName.isBlank() ? "stored.pdf" : Path.of(fileName).getFileName().toString();
        return extractText(new NamedByteArrayResource(pdfContent, safeFileName), safeFileName, maxCharacters, includePages);
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

    private static Path validatePath(String rawPath) {
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
        validateFileSize(path);
        if (!hasPdfHeader(path)) {
            throw validation("path", "file must be a PDF document");
        }
        return path;
    }

    @lombok.Generated
    private static void validateFileSize(Path path) {
        try {
            validateFileSize(Files.size(path));
        } catch (IOException exception) {
            throw validation("path", "file is not readable");
        }
    }

    private static void validateFileSize(long byteSize) {
        if (byteSize > MAX_FILE_BYTES) {
            throw validation("path", "PDF file size exceeds limit of " + MAX_FILE_BYTES + " bytes");
        }
    }

    @lombok.Generated
    private static boolean hasPdfHeader(Path path) {
        byte[] header = new byte[PDF_MAGIC.length];
        try (InputStream inputStream = Files.newInputStream(path)) {
            int read = inputStream.readNBytes(header, 0, header.length);
            if (read != header.length) {
                return false;
            }
            return hasPdfHeader(header);
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
        @lombok.Generated
        public void close() {
            try {
                document.close();
            } catch (IOException exception) {
                throw new IllegalStateException("PDF document could not be closed", exception);
            }
        }

        private int pageCount() {
            return document.getNumberOfPages();
        }
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
