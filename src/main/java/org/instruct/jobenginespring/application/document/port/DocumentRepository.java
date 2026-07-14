package org.instruct.jobenginespring.application.document.port;

import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    StoredDocumentMetadata saveFile(StoredDocumentFile file);

    Optional<StoredDocumentMetadata> findFileMetadataById(UUID fileId);

    Optional<StoredDocumentFile> findFileContentById(UUID fileId);

    Optional<PdfExtractionRecord> findPdfExtractionByFileId(UUID fileId);

    PdfExtractionRecord savePdfExtraction(PdfExtractionRecord extraction);

    PdfExtractionRecord updatePdfExtraction(PdfExtractionRecord extraction);

    /**
     * Deletes a document only when no profile source or generated-resume link references it.
     * An underlying blob is removed only when the deleted document was its final reference.
     */
    boolean deleteFileIfUnreferenced(UUID fileId);

    /**
     * Checks whether any generated-resume reference still requires {@code filePath}.
     *
     * @return {@code true} when physical deletion is safe, or {@code false} when the path must be preserved
     */
    boolean prepareGeneratedFileCleanup(String filePath);
}
