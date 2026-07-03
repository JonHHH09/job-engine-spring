package org.instruct.jobenginespring.application.document.port;

import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    StoredDocumentMetadata saveFile(StoredDocumentFile file);

    Optional<StoredDocumentMetadata> findFileMetadataById(UUID fileId);

    Optional<StoredDocumentMetadata> findFileMetadataBySha256(String sha256);

    Optional<StoredDocumentFile> findFileContentById(UUID fileId);

    Optional<PdfExtractionRecord> findPdfExtractionByFileId(UUID fileId);

    PdfExtractionRecord savePdfExtraction(PdfExtractionRecord extraction);
}
