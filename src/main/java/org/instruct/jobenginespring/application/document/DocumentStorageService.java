package org.instruct.jobenginespring.application.document;

import lombok.NonNull;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.security.McpAccessPolicy;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class DocumentStorageService {

    public static final String PDF_MEDIA_TYPE = "application/pdf";
    private static final String PDF_EXTRACTOR = "spring-ai-page-pdf-document-reader";
    private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};

    @NonNull
    private final DocumentRepository documentRepository;
    @NonNull
    private final PdfTextExtractionService pdfTextExtractionService;
    @NonNull
    private final McpAccessPolicy accessPolicy;
    @NonNull
    private final LocalFileImportPolicy importPolicy;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public DocumentStorageService(
            DocumentRepository documentRepository,
            PdfTextExtractionService pdfTextExtractionService,
            McpAccessPolicy accessPolicy,
            @Value("${job-engine.document.import-root:" + LocalFileImportPolicy.DEFAULT_IMPORT_ROOT + "}") String importRoot
    ) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.pdfTextExtractionService = Objects.requireNonNull(pdfTextExtractionService, "pdfTextExtractionService must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.importPolicy = LocalFileImportPolicy.rootedAt(importRoot);
    }

    DocumentStorageService(DocumentRepository documentRepository, PdfTextExtractionService pdfTextExtractionService, Clock clock) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.pdfTextExtractionService = Objects.requireNonNull(pdfTextExtractionService, "pdfTextExtractionService must not be null");
        this.accessPolicy = McpAccessPolicy.permitAllForTests();
        this.importPolicy = LocalFileImportPolicy.unrestrictedForTests();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public StoredDocumentMetadata storeDocumentFile(StoreDocumentFileRequest request) {
        StoreDocumentFileRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        accessPolicy.authorize(safeRequest.accessToken(), "store_document_file");
        Path path = importPolicy.requireAllowed(validatePath(safeRequest.path()));
        String mediaType = resolveMediaType(safeRequest.mediaType());
        return storeValidatedPath(path, mediaType);
    }

    StoredDocumentMetadata storeGeneratedDocumentFile(Path generatedPath, String mediaType) {
        Path path = validatePath(Objects.requireNonNull(generatedPath, "generatedPath must not be null").toString());
        return storeValidatedPath(path, resolveMediaType(mediaType));
    }

    private StoredDocumentMetadata storeValidatedPath(Path path, String mediaType) {
        byte[] content = readContent(path);
        validateByteSize(content.length);
        if (PDF_MEDIA_TYPE.equals(mediaType)) {
            validatePdfHeader(content);
        }
        String sha256 = sha256(content);
        return saveNewFile(path, mediaType, content, sha256);
    }

    @Transactional(readOnly = true)
    public StoredDocumentMetadata getDocumentMetadata(UUID documentId, String accessToken) {
        accessPolicy.authorize(accessToken, "get_document_metadata");
        return documentRepository.findFileMetadataById(validateDocumentId(documentId))
                .orElseThrow(() -> notFound(documentId));
    }

    @Transactional
    public StoredPdfTextExtractionResult extractStoredPdfText(ExtractStoredPdfTextRequest request) {
        ExtractStoredPdfTextRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        accessPolicy.authorize(safeRequest.accessToken(), "extract_stored_pdf_text");
        UUID documentId = validateDocumentId(safeRequest.documentId());
        StoredDocumentFile file = documentRepository.findFileContentById(documentId)
                .orElseThrow(() -> notFound(documentId));
        if (!PDF_MEDIA_TYPE.equals(file.mediaType())) {
            throw validation("documentId", "stored document must have media type " + PDF_MEDIA_TYPE);
        }

        if (safeRequest.persistExtraction() != null && safeRequest.persistExtraction()) {
            return documentRepository.findPdfExtractionByFileId(file.id())
                    .map(existing -> new StoredPdfTextExtractionResult(
                            file.metadata(),
                            existing.id(),
                            toExtractionResult(file.originalFileName(), existing)
                    ))
                    .orElseGet(() -> extractAndPersist(file, safeRequest));
        }

        PdfTextExtractionResult extraction = pdfTextExtractionService.extractText(
                file.content(),
                file.originalFileName(),
                safeRequest.maxCharacters(),
                safeRequest.includePages()
        );
        return new StoredPdfTextExtractionResult(file.metadata(), null, extraction);
    }

    private StoredDocumentMetadata saveNewFile(Path path, String mediaType, byte[] content, String sha256) {
        Instant now = clock.instant();
        StoredDocumentFile file = new StoredDocumentFile(
                UUID.randomUUID(),
                path.getFileName().toString(),
                mediaType,
                content.length,
                sha256,
                content,
                now,
                now
        );
        return documentRepository.saveFile(file);
    }

    private StoredPdfTextExtractionResult extractAndPersist(StoredDocumentFile file, ExtractStoredPdfTextRequest request) {
        PdfTextExtractionResult extraction = pdfTextExtractionService.extractText(
                file.content(),
                file.originalFileName(),
                request.maxCharacters(),
                request.includePages()
        );
        UUID extractionId = UUID.randomUUID();
        Instant now = clock.instant();
        PdfExtractionRecord saved = documentRepository.savePdfExtraction(new PdfExtractionRecord(
                extractionId,
                file.id(),
                PDF_EXTRACTOR,
                extraction.characterCount(),
                extraction.pageCount(),
                extraction.truncated(),
                extraction.text(),
                now
        ));
        return new StoredPdfTextExtractionResult(file.metadata(), saved.id(), extraction);
    }

    private static PdfTextExtractionResult toExtractionResult(String fileName, PdfExtractionRecord extraction) {
        return new PdfTextExtractionResult(
                fileName,
                extraction.pageCount(),
                extraction.characterCount(),
                extraction.truncated(),
                extraction.extractedText(),
                java.util.List.of()
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
        return path;
    }

    private static UUID validateDocumentId(UUID documentId) {
        return Objects.requireNonNull(documentId, "documentId must not be null");
    }

    private static String resolveMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return PDF_MEDIA_TYPE;
        }
        return mediaType.strip().toLowerCase();
    }

    @lombok.Generated
    private static byte[] readContent(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw validation("path", "file is not readable");
        }
    }

    private static void validateByteSize(long byteSize) {
        if (byteSize <= 0) {
            throw validation("path", "file must not be empty");
        }
        if (byteSize > PdfTextExtractionService.MAX_FILE_BYTES) {
            throw validation("path", "file size exceeds limit of " + PdfTextExtractionService.MAX_FILE_BYTES + " bytes");
        }
    }

    private static void validatePdfHeader(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            throw validation("path", "file must be a PDF document");
        }
        for (int index = 0; index < PDF_MAGIC.length; index++) {
            if (content[index] != PDF_MAGIC[index]) {
                throw validation("path", "file must be a PDF document");
            }
        }
    }

    @lombok.Generated
    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new ApplicationException(
                    ApplicationErrorCode.INTERNAL_ERROR,
                    ApplicationErrorCode.INTERNAL_ERROR.defaultMessage(),
                    Map.of(),
                    exception
            );
        }
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid document storage request",
                Map.of("field", field, "reason", reason),
                null
        );
    }

    private static ApplicationException notFound(UUID documentId) {
        return new ApplicationException(
                ApplicationErrorCode.NOT_FOUND,
                "Stored document was not found",
                Map.of("documentId", String.valueOf(documentId)),
                null
        );
    }

    public record StoreDocumentFileRequest(String path, String mediaType, String accessToken) {
        public StoreDocumentFileRequest(String path, String mediaType) {
            this(path, mediaType, null);
        }
    }

    public record ExtractStoredPdfTextRequest(
            UUID documentId,
            Integer maxCharacters,
            Boolean includePages,
            Boolean persistExtraction,
            String accessToken
    ) {
        public ExtractStoredPdfTextRequest(UUID documentId, Integer maxCharacters, Boolean includePages, Boolean persistExtraction) {
            this(documentId, maxCharacters, includePages, persistExtraction, null);
        }
    }

    public record StoredPdfTextExtractionResult(
            StoredDocumentMetadata document,
            UUID extractionId,
            PdfTextExtractionResult extraction
    ) {
    }
}
