package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.instruct.jobenginespring.application.profile.port.ProfileResumeDocumentRepository;
import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.profile.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresProfileResumeDocumentRepository implements ProfileResumeDocumentRepository {

    private static final RowMapper<ProfileResumeDocument> RESUME_DOCUMENT_MAPPER =
            PostgresProfileResumeDocumentRepository::mapResumeDocument;

    private final JdbcClient jdbc;

    public PostgresProfileResumeDocumentRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public ProfileResumeDocument save(ProfileResumeDocument resumeDocument) {
        return replace(resumeDocument).saved();
    }

    @Override
    public Replacement replace(ProfileResumeDocument resumeDocument) {
        Objects.requireNonNull(resumeDocument, "resumeDocument must not be null");
        lockProfile(resumeDocument.profileId());
        Optional<ProfileResumeDocument> previous = findByProfileIdAndResumeType(
                resumeDocument.profileId(),
                resumeDocument.resumeType()
        );
        jdbc.sql("""
                        INSERT INTO profile.profile_resume_documents (
                            id, profile_id, document_id, file_path, resume_type, created_at, updated_at
                        ) VALUES (
                            :id, :profileId, :documentId, :filePath, :resumeType, :createdAt, :updatedAt
                        )
                        ON CONFLICT (profile_id, resume_type) DO UPDATE SET
                            document_id = EXCLUDED.document_id,
                            file_path = EXCLUDED.file_path,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("id", resumeDocument.id())
                .param("profileId", resumeDocument.profileId())
                .param("documentId", resumeDocument.documentId())
                .param("filePath", resumeDocument.filePath())
                .param("resumeType", resumeDocument.resumeType())
                .param("createdAt", Timestamp.from(resumeDocument.createdAt()))
                .param("updatedAt", Timestamp.from(resumeDocument.updatedAt()))
                .update();
        ProfileResumeDocument saved = findByProfileIdAndResumeType(
                resumeDocument.profileId(),
                resumeDocument.resumeType()
        ).orElseThrow();
        return new Replacement(saved, previous);
    }

    @Override
    public Optional<ProfileResumeDocument> findByProfileIdAndResumeType(UUID profileId, String resumeType) {
        return jdbc.sql("""
                        SELECT id, profile_id, document_id, file_path, resume_type, created_at, updated_at
                        FROM profile.profile_resume_documents
                        WHERE profile_id = :profileId
                          AND resume_type = :resumeType
                        """)
                .param("profileId", profileId)
                .param("resumeType", resumeType)
                .query(RESUME_DOCUMENT_MAPPER)
                .optional();
    }

    @Override
    public List<ProfileResumeDocument> lockAndFindAllByProfileId(UUID profileId) {
        lockProfile(profileId);
        return jdbc.sql("""
                        SELECT id, profile_id, document_id, file_path, resume_type, created_at, updated_at
                        FROM profile.profile_resume_documents
                        WHERE profile_id = :profileId
                        ORDER BY resume_type, id
                        """)
                .param("profileId", profileId)
                .query(RESUME_DOCUMENT_MAPPER)
                .list();
    }

    private void lockProfile(UUID profileId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", "generated-resume:" + profileId)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private static ProfileResumeDocument mapResumeDocument(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProfileResumeDocument(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("file_path"),
                resultSet.getString("resume_type"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
