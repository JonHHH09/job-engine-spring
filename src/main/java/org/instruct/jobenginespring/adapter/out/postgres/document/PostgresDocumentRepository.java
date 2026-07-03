package org.instruct.jobenginespring.adapter.out.postgres.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
    public StoredDocumentMetadata saveFile(StoredDocumentFile file) {
        Objects.requireNonNull(file, "file must not be null");
        try {
            namedJdbc.update("""
                    INSERT INTO document.files (
                        id, original_file_name, media_type, byte_size, sha256, content, created_at, updated_at
                    ) VALUES (
                        :id, :originalFileName, :mediaType, :byteSize, :sha256, :content, :createdAt, :updatedAt
                    )
                    """, fileParameters(file));
            return file.metadata();
        } catch (DuplicateKeyException exception) {
            Optional<StoredDocumentMetadata> existing = findFileMetadataBySha256(file.sha256());
            if (existing.isPresent()) {
                return existing.get();
            }
            throw exception;
        }
    }

    @Override
    public Optional<StoredDocumentMetadata> findFileMetadataById(UUID fileId) {
        return jdbc.sql("""
                        SELECT id, original_file_name, media_type, byte_size, sha256, created_at, updated_at
                        FROM document.files
                        WHERE id = :fileId
                        """)
                .param("fileId", fileId)
                .query(METADATA_MAPPER)
                .optional();
    }

    @Override
    public Optional<StoredDocumentMetadata> findFileMetadataBySha256(String sha256) {
        return jdbc.sql("""
                        SELECT id, original_file_name, media_type, byte_size, sha256, created_at, updated_at
                        FROM document.files
                        WHERE sha256 = :sha256
                        """)
                .param("sha256", sha256)
                .query(METADATA_MAPPER)
                .optional();
    }

    @Override
    public Optional<StoredDocumentFile> findFileContentById(UUID fileId) {
        return jdbc.sql("""
                        SELECT id, original_file_name, media_type, byte_size, sha256, content, created_at, updated_at
                        FROM document.files
                        WHERE id = :fileId
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
    public PdfExtractionRecord savePdfExtraction(PdfExtractionRecord extraction) {
        Objects.requireNonNull(extraction, "extraction must not be null");
        namedJdbc.update("""
                INSERT INTO document.pdf_extractions (
                    id, file_id, extractor, character_count, page_count, truncated, extracted_text, created_at
                ) VALUES (
                    :id, :fileId, :extractor, :characterCount, :pageCount, :truncated, :extractedText, :createdAt
                )
                """, extractionParameters(extraction));
        return jdbc.sql("""
                        SELECT id, file_id, extractor, character_count, page_count, truncated, extracted_text, created_at
                        FROM document.pdf_extractions
                        WHERE id = :id
                        """)
                .param("id", extraction.id())
                .query(EXTRACTION_MAPPER)
                .single();
    }

    private static MapSqlParameterSource fileParameters(StoredDocumentFile file) {
        return new MapSqlParameterSource()
                .addValue("id", file.id())
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
