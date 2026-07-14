package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository.LinkedPdfSource;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.profile.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresProfilePdfSourceRepository implements ProfilePdfSourceRepository {

    private static final RowMapper<ProfilePdfSource> SOURCE_MAPPER = PostgresProfilePdfSourceRepository::mapSource;

    private final JdbcClient jdbc;

    public PostgresProfilePdfSourceRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void acquireIngestionLock(String hashedLockKey) {
        Objects.requireNonNull(hashedLockKey, "hashedLockKey must not be null");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", hashedLockKey)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    @Override
    public ProfilePdfSource save(ProfilePdfSource source) {
        Objects.requireNonNull(source, "source must not be null");
        int inserted = jdbc.sql("""
                        INSERT INTO profile.profile_pdf_sources (id, profile_id, pdf_extraction_id, source_type, created_at)
                        VALUES (:id, :profileId, :pdfExtractionId, :sourceType, :createdAt)
                        """)
                .param("id", source.id())
                .param("profileId", source.profileId())
                .param("pdfExtractionId", source.pdfExtractionId())
                .param("sourceType", source.sourceType())
                .param("createdAt", Timestamp.from(source.createdAt()))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("Profile PDF source insert count was not one");
        }
        return findByProfileId(source.profileId()).orElseThrow();
    }

    @Override
    public InsertResult insertOrFind(ProfilePdfSource source) {
        Objects.requireNonNull(source, "source must not be null");
        var inserted = jdbc.sql("""
                        INSERT INTO profile.profile_pdf_sources (id, profile_id, pdf_extraction_id, source_type, created_at)
                        VALUES (:id, :profileId, :pdfExtractionId, :sourceType, :createdAt)
                        ON CONFLICT DO NOTHING
                        RETURNING id, profile_id, pdf_extraction_id, source_type, created_at
                        """)
                .param("id", source.id())
                .param("profileId", source.profileId())
                .param("pdfExtractionId", source.pdfExtractionId())
                .param("sourceType", source.sourceType())
                .param("createdAt", Timestamp.from(source.createdAt()))
                .query(SOURCE_MAPPER)
                .optional();
        if (inserted.isPresent()) {
            return new InsertResult(inserted.orElseThrow(), true);
        }

        // A separate statement gets a fresh READ COMMITTED snapshot after a concurrent
        // winner has committed and ON CONFLICT has stopped waiting for it.
        return findByPdfExtractionId(source.pdfExtractionId())
                .or(() -> findByProfileId(source.profileId()))
                .map(winner -> new InsertResult(winner, false))
                .orElseThrow(() -> new IllegalStateException(
                        "Profile PDF source insert conflicted without a profile or extraction winner"
                ));
    }

    @Override
    public Optional<ProfilePdfSource> findByProfileId(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, profile_id, pdf_extraction_id, source_type, created_at
                        FROM profile.profile_pdf_sources
                        WHERE profile_id = :profileId
                        """)
                .param("profileId", profileId)
                .query(SOURCE_MAPPER)
                .optional();
    }

    @Override
    public Optional<ProfilePdfSource> findByPdfExtractionId(UUID pdfExtractionId) {
        return jdbc.sql("""
                        SELECT id, profile_id, pdf_extraction_id, source_type, created_at
                        FROM profile.profile_pdf_sources
                        WHERE pdf_extraction_id = :pdfExtractionId
                        """)
                .param("pdfExtractionId", pdfExtractionId)
                .query(SOURCE_MAPPER)
                .optional();
    }

    @Override
    public Optional<ProfilePdfSource> findByDocumentSha256(String sha256) {
        return jdbc.sql("""
                        SELECT source.id, source.profile_id, source.pdf_extraction_id, source.source_type, source.created_at
                        FROM profile.profile_pdf_sources source
                        JOIN document.pdf_extractions extraction ON extraction.id = source.pdf_extraction_id
                        JOIN document.documents document ON document.id = extraction.file_id
                        JOIN document.blobs blob ON blob.id = document.blob_id
                        WHERE blob.sha256 = :sha256
                        ORDER BY source.created_at DESC, source.id
                        LIMIT 1
                        """)
                .param("sha256", sha256)
                .query(SOURCE_MAPPER)
                .optional();
    }

    @Override
    public Optional<LinkedPdfSource> findLinkedByProfileId(UUID profileId) {
        return findLinked("source.profile_id = :value", profileId);
    }

    @Override
    public Optional<LinkedPdfSource> findLinkedByPdfExtractionId(UUID pdfExtractionId) {
        return findLinked("source.pdf_extraction_id = :value", pdfExtractionId);
    }

    @Override
    public Optional<LinkedPdfSource> findLinkedByDocumentSha256(String sha256) {
        return findLinked("blob.sha256 = :value", sha256);
    }

    private Optional<LinkedPdfSource> findLinked(String predicate, Object value) {
        return jdbc.sql("""
                        SELECT source.id, source.profile_id, source.pdf_extraction_id, source.source_type, source.created_at,
                               document.id AS document_id, document.original_file_name,
                               extraction.page_count, extraction.character_count, extraction.truncated
                        FROM profile.profile_pdf_sources source
                        JOIN document.pdf_extractions extraction ON extraction.id = source.pdf_extraction_id
                        JOIN document.documents document ON document.id = extraction.file_id
                        JOIN document.blobs blob ON blob.id = document.blob_id
                        WHERE %s
                        ORDER BY source.created_at DESC, source.id
                        LIMIT 1
                        """.formatted(predicate))
                .param("value", value)
                .query((resultSet, rowNumber) -> new LinkedPdfSource(
                        mapSource(resultSet, rowNumber),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("original_file_name"),
                        resultSet.getInt("page_count"),
                        resultSet.getInt("character_count"),
                        resultSet.getBoolean("truncated")
                ))
                .optional();
    }

    private static ProfilePdfSource mapSource(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProfilePdfSource(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getObject("pdf_extraction_id", UUID.class),
                resultSet.getString("source_type"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
