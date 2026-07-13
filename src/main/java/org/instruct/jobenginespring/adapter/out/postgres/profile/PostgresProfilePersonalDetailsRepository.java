package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.instruct.jobenginespring.application.profile.port.ProfilePersonalDetailsRepository;
import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.profile.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresProfilePersonalDetailsRepository implements ProfilePersonalDetailsRepository {

    private final JdbcClient jdbc;

    public PostgresProfilePersonalDetailsRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public Optional<ProfilePersonalDetails> findByProfileId(UUID profileId) {
        return jdbc.sql("""
                        SELECT profile_id, date_of_birth, nationality, photo_document_id, created_at, updated_at
                        FROM profile.profile_personal_details
                        WHERE profile_id = :profileId
                        """)
                .param("profileId", profileId)
                .query(this::map)
                .optional();
    }

    @Override
    public ProfilePersonalDetails save(ProfilePersonalDetails details) {
        ProfilePersonalDetails safe = Objects.requireNonNull(details, "details must not be null");
        jdbc.sql("""
                        INSERT INTO profile.profile_personal_details (
                            profile_id, date_of_birth, nationality, photo_document_id, created_at, updated_at
                        ) VALUES (
                            :profileId, :dateOfBirth, :nationality, :photoDocumentId, :createdAt, :updatedAt
                        )
                        ON CONFLICT (profile_id) DO UPDATE SET
                            date_of_birth = EXCLUDED.date_of_birth,
                            nationality = EXCLUDED.nationality,
                            photo_document_id = EXCLUDED.photo_document_id,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("profileId", safe.profileId())
                .param("dateOfBirth", safe.dateOfBirth() == null ? null : Date.valueOf(safe.dateOfBirth()))
                .param("nationality", safe.nationality())
                .param("photoDocumentId", safe.photoDocumentId())
                .param("createdAt", Timestamp.from(safe.createdAt()))
                .param("updatedAt", Timestamp.from(safe.updatedAt()))
                .update();
        return findByProfileId(safe.profileId()).orElseThrow();
    }

    private ProfilePersonalDetails map(ResultSet rs, int rowNum) throws SQLException {
        Date dob = rs.getDate("date_of_birth");
        LocalDate dateOfBirth = dob == null ? null : dob.toLocalDate();
        UUID photoDocumentId = (UUID) rs.getObject("photo_document_id");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new ProfilePersonalDetails(
                (UUID) rs.getObject("profile_id"),
                dateOfBirth,
                rs.getString("nationality"),
                photoDocumentId,
                createdAt,
                updatedAt
        );
    }
}
