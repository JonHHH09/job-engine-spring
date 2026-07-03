package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
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
    public ProfilePdfSource save(ProfilePdfSource source) {
        Objects.requireNonNull(source, "source must not be null");
        jdbc.sql("""
                        INSERT INTO profile.profile_pdf_sources (id, profile_id, pdf_extraction_id, source_type, created_at)
                        VALUES (:id, :profileId, :pdfExtractionId, :sourceType, :createdAt)
                        """)
                .param("id", source.id())
                .param("profileId", source.profileId())
                .param("pdfExtractionId", source.pdfExtractionId())
                .param("sourceType", source.sourceType())
                .param("createdAt", Timestamp.from(source.createdAt()))
                .update();
        return findByProfileId(source.profileId()).orElseThrow();
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
