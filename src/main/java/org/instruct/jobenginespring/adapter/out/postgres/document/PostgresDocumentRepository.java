package org.instruct.jobenginespring.adapter.out.postgres.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.document.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresDocumentRepository implements DocumentRepository {

    private static final RowMapper<StoredDocumentMetadata> METADATA_MAPPER = PostgresDocumentRepository::mapMetadata;
    private static final RowMapper<StoredDocumentFile> FILE_MAPPER = PostgresDocumentRepository::mapFile;
    private static final RowMapper<PdfExtractionRecord> EXTRACTION_MAPPER = PostgresDocumentRepository::mapExtraction;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations namedJdbc;

    public PostgresDocumentRepository(NamedParameterJdbcOperations namedJdbc) {
        this.namedJdbc = Objects.requireNonNull(namedJdbc, "namedJdbc must not be null");
        this.jdbc = JdbcClient.create(namedJdbc);
    }

    @Override
    @Transactional
    public StoredDocumentMetadata saveFile(StoredDocumentFile file) {
        Objects.requireNonNull(file, "file must not be null");
        namedJdbc.update("""
                WITH selected_blob AS (
                    INSERT INTO document.blobs (id, sha256, byte_size, content, created_at)
                    VALUES (:blobId, :sha256, :byteSize, :content, :createdAt)
                    ON CONFLICT (sha256) DO UPDATE SET sha256 = EXCLUDED.sha256
                    RETURNING id
                )
                INSERT INTO document.documents (
                    id, blob_id, original_file_name, media_type, created_at, updated_at
                )
                SELECT :id, selected_blob.id, :originalFileName, :mediaType, :createdAt, :updatedAt
                FROM selected_blob
                """, fileParameters(file));
        return findFileMetadataById(file.id()).orElseThrow();
    }

    @Override
    public Optional<StoredDocumentMetadata> findFileMetadataById(UUID fileId) {
        return jdbc.sql("""
                        SELECT stored_document.id,
                               stored_document.original_file_name,
                               stored_document.media_type,
                               blob.byte_size,
                               blob.sha256,
                               stored_document.created_at,
                               stored_document.updated_at
                        FROM document.documents stored_document
                        JOIN document.blobs blob ON blob.id = stored_document.blob_id
                        WHERE stored_document.id = :fileId
                        """)
                .param("fileId", fileId)
                .query(METADATA_MAPPER)
                .optional();
    }

    @Override
    public Optional<StoredDocumentMetadata> findFileMetadataBySha256(String sha256) {
        return jdbc.sql("""
                        SELECT stored_document.id,
                               stored_document.original_file_name,
                               stored_document.media_type,
                               blob.byte_size,
                               blob.sha256,
                               stored_document.created_at,
                               stored_document.updated_at
                        FROM document.documents stored_document
                        JOIN document.blobs blob ON blob.id = stored_document.blob_id
                        WHERE blob.sha256 = :sha256
                        ORDER BY stored_document.created_at DESC, stored_document.id
                        LIMIT 1
                        """)
                .param("sha256", sha256)
                .query(METADATA_MAPPER)
                .optional();
    }

    @Override
    public Optional<StoredDocumentFile> findFileContentById(UUID fileId) {
        return jdbc.sql("""
                        SELECT stored_document.id,
                               stored_document.original_file_name,
                               stored_document.media_type,
                               blob.byte_size,
                               blob.sha256,
                               blob.content,
                               stored_document.created_at,
                               stored_document.updated_at
                        FROM document.documents stored_document
                        JOIN document.blobs blob ON blob.id = stored_document.blob_id
                        WHERE stored_document.id = :fileId
                        """)
                .param("fileId", fileId)
                .query(FILE_MAPPER)
                .optional();
    }

    @Override
    public Optional<PdfExtractionRecord> findPdfExtractionByFileId(UUID fileId) {
        return jdbc.sql("""
                        SELECT id, file_id, extractor, character_count, page_count, truncated, extracted_text, created_at
                        FROM document.pdf_extractions
                        WHERE file_id = :fileId
                        """)
                .param("fileId", fileId)
                .query(EXTRACTION_MAPPER)
                .optional();
    }

    @Override
    @Transactional
    public PdfExtractionRecord savePdfExtraction(PdfExtractionRecord extraction) {
        Objects.requireNonNull(extraction, "extraction must not be null");
        MapSqlParameterSource parameters = extractionParameters(extraction);
        return jdbc.sql("""
                        INSERT INTO document.pdf_extractions (
                            id, file_id, extractor, character_count, page_count, truncated, extracted_text, created_at
                        ) VALUES (
                            :id, :fileId, :extractor, :characterCount, :pageCount, :truncated, :extractedText, :createdAt
                        )
                        ON CONFLICT (file_id) DO UPDATE SET file_id = EXCLUDED.file_id
                        RETURNING id, file_id, extractor, character_count, page_count, truncated, extracted_text, created_at
                        """)
                .params(parameters.getValues())
                .query(EXTRACTION_MAPPER)
                .single();
    }

    @Override
    @Transactional
    public PdfExtractionRecord updatePdfExtraction(PdfExtractionRecord extraction) {
        Objects.requireNonNull(extraction, "extraction must not be null");
        namedJdbc.update("""
                UPDATE document.pdf_extractions
                SET extractor = :extractor,
                    character_count = :characterCount,
                    page_count = :pageCount,
                    truncated = :truncated,
                    extracted_text = :extractedText,
                    created_at = :createdAt
                WHERE id = :id AND file_id = :fileId
                """, extractionParameters(extraction));
        return findExtractionById(extraction.id());
    }

    private PdfExtractionRecord findExtractionById(UUID extractionId) {
        return jdbc.sql("""
                        SELECT id, file_id, extractor, character_count, page_count, truncated, extracted_text, created_at
                        FROM document.pdf_extractions
                        WHERE id = :id
                        """)
                .param("id", extractionId)
                .query(EXTRACTION_MAPPER)
                .single();
    }

    @Override
    @Transactional
    public boolean deleteFileIfUnreferenced(UUID fileId) {
        Optional<UUID> deletedBlobId = jdbc.sql("""
                        DELETE FROM document.documents stored_document
                        WHERE stored_document.id = :fileId
                          AND NOT EXISTS (
                              SELECT 1
                              FROM profile.profile_resume_documents resume_document
                              WHERE resume_document.document_id = stored_document.id
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM resume.resume_variants resume_variant
                              WHERE resume_variant.document_id = stored_document.id
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM profile.profile_personal_details personal_details
                              WHERE personal_details.photo_document_id = stored_document.id
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM document.pdf_extractions extraction
                              JOIN profile.profile_pdf_sources source
                                ON source.pdf_extraction_id = extraction.id
                              WHERE extraction.file_id = stored_document.id
                          )
                        RETURNING stored_document.blob_id
                        """)
                .param("fileId", fileId)
                .query(UUID.class)
                .optional();
        deletedBlobId.ifPresent(blobId -> jdbc.sql("""
                        DELETE FROM document.blobs blob
                        WHERE blob.id = :blobId
                          AND NOT EXISTS (
                              SELECT 1
                              FROM document.documents stored_document
                              WHERE stored_document.blob_id = blob.id
                          )
                        """)
                .param("blobId", blobId)
                .update());
        return deletedBlobId.isPresent();
    }

    @Override
    @Transactional
    public boolean prepareGeneratedFileCleanup(String filePath) {
        String safeFilePath = Objects.requireNonNull(filePath, "filePath must not be null");
        String fileName = Path.of(safeFilePath).getFileName().toString();
        if (generatedResumeReferenceExists(safeFilePath)) {
            return false;
        }

        List<UUID> candidateIds = jdbc.sql("""
                        SELECT id
                        FROM document.documents
                        WHERE original_file_name = :fileName
                        ORDER BY id
                        FOR UPDATE
                        """)
                .param("fileName", fileName)
                .query(UUID.class)
                .list();
        for (UUID candidateId : candidateIds) {
            if (!deleteFileIfUnreferenced(candidateId)) {
                return false;
            }
        }
        return true;
    }

    private boolean generatedResumeReferenceExists(String filePath) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM profile.profile_resume_documents
                            WHERE file_path = :filePath
                            UNION ALL
                            SELECT 1
                            FROM resume.resume_variants
                            WHERE file_path = :filePath
                        )
                        """)
                .param("filePath", filePath)
                .query(Boolean.class)
                .single();
    }

    private static MapSqlParameterSource fileParameters(StoredDocumentFile file) {
        return new MapSqlParameterSource()
                .addValue("id", file.id())
                .addValue("blobId", UUID.randomUUID())
                .addValue("originalFileName", file.originalFileName())
                .addValue("mediaType", file.mediaType())
                .addValue("byteSize", file.byteSize())
                .addValue("sha256", file.sha256())
                .addValue("content", file.content())
                .addValue("createdAt", Timestamp.from(file.createdAt()))
                .addValue("updatedAt", Timestamp.from(file.updatedAt()));
    }

    private static MapSqlParameterSource extractionParameters(PdfExtractionRecord extraction) {
        return new MapSqlParameterSource()
                .addValue("id", extraction.id())
                .addValue("fileId", extraction.fileId())
                .addValue("extractor", extraction.extractor())
                .addValue("characterCount", extraction.characterCount())
                .addValue("pageCount", extraction.pageCount())
                .addValue("truncated", extraction.truncated())
                .addValue("extractedText", extraction.extractedText())
                .addValue("createdAt", Timestamp.from(extraction.createdAt()));
    }

    private static StoredDocumentMetadata mapMetadata(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredDocumentMetadata(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("original_file_name"),
                resultSet.getString("media_type"),
                resultSet.getLong("byte_size"),
                resultSet.getString("sha256"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static StoredDocumentFile mapFile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredDocumentFile(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("original_file_name"),
                resultSet.getString("media_type"),
                resultSet.getLong("byte_size"),
                resultSet.getString("sha256"),
                resultSet.getBytes("content"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static PdfExtractionRecord mapExtraction(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PdfExtractionRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("file_id", UUID.class),
                resultSet.getString("extractor"),
                resultSet.getInt("character_count"),
                resultSet.getInt("page_count"),
                resultSet.getBoolean("truncated"),
                resultSet.getString("extracted_text"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
