package org.instruct.jobenginespring.adapter.out.postgres.coverletter;

import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.domain.coverletter.CoverLetter;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterParagraph;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.cover-letter.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresCoverLetterRepository implements CoverLetterRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations namedJdbc;

    public PostgresCoverLetterRepository(NamedParameterJdbcOperations namedJdbc) {
        this.namedJdbc = Objects.requireNonNull(namedJdbc, "namedJdbc must not be null");
        this.jdbc = JdbcClient.create(namedJdbc);
    }

    @Override
    public Optional<CoverLetter> findById(UUID coverLetterId) {
        return jdbc.sql(parentSelect() + " WHERE id = :coverLetterId")
                .param("coverLetterId", coverLetterId)
                .query(this::mapCoverLetter)
                .optional();
    }

    @Override
    public Optional<CoverLetter> findByProfileJobResume(UUID profileId, UUID jobId, UUID resumeId) {
        return jdbc.sql(parentSelect() + " WHERE profile_id = :profileId AND job_id = :jobId AND resume_id = :resumeId")
                .param("profileId", profileId)
                .param("jobId", jobId)
                .param("resumeId", resumeId)
                .query(this::mapCoverLetter)
                .optional();
    }

    @Override
    public List<CoverLetterVariant> findVariants(UUID coverLetterId) {
        return jdbc.sql("""
                        SELECT id, cover_letter_id, format, language, document_id, file_path,
                               subject, salutation, closing, signature, created_at, updated_at
                        FROM cover_letter.cover_letter_variants
                        WHERE cover_letter_id = :coverLetterId
                        ORDER BY format, language
                        """)
                .param("coverLetterId", coverLetterId)
                .query(this::mapVariant)
                .list();
    }

    @Override
    @Transactional
    public ReplaceResult replace(CoverLetterAggregateWrite write) {
        CoverLetterAggregateWrite safe = Objects.requireNonNull(write, "write must not be null");
        CoverLetter coverLetter = safe.coverLetter();
        lockIdentity(coverLetter.profileId(), coverLetter.jobId());
        validateCurrentSources(coverLetter);

        List<CoverLetterVariant> previousVariants = findByProfileJobResume(
                coverLetter.profileId(), coverLetter.jobId(), coverLetter.resumeId()
        ).map(existing -> {
            List<CoverLetterVariant> previous = findVariants(existing.id());
            jdbc.sql("DELETE FROM cover_letter.cover_letters WHERE id = :id")
                    .param("id", existing.id())
                    .update();
            return previous;
        }).orElseGet(List::of);

        jdbc.sql("""
                        INSERT INTO cover_letter.cover_letters (
                            id, profile_id, job_id, resume_id, profile_revision, job_revision, resume_revision,
                            created_at, updated_at
                        ) VALUES (
                            :id, :profileId, :jobId, :resumeId, :profileRevision, :jobRevision, :resumeRevision,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("id", coverLetter.id())
                .param("profileId", coverLetter.profileId())
                .param("jobId", coverLetter.jobId())
                .param("resumeId", coverLetter.resumeId())
                .param("profileRevision", Timestamp.from(coverLetter.profileRevision()))
                .param("jobRevision", Timestamp.from(coverLetter.jobRevision()))
                .param("resumeRevision", Timestamp.from(coverLetter.resumeRevision()))
                .param("createdAt", Timestamp.from(coverLetter.createdAt()))
                .param("updatedAt", Timestamp.from(coverLetter.updatedAt()))
                .update();

        CoverLetterVariant variant = safe.variant().variant();
        jdbc.sql("""
                        INSERT INTO cover_letter.cover_letter_variants (
                            id, cover_letter_id, format, language, document_id, file_path,
                            subject, salutation, closing, signature, created_at, updated_at
                        ) VALUES (
                            :id, :coverLetterId, :format, :language, :documentId, :filePath,
                            :subject, :salutation, :closing, :signature, :createdAt, :updatedAt
                        )
                        """)
                .params(variantParameters(variant).getValues())
                .update();

        batchParagraphs(safe.variant().paragraphs());
        return new ReplaceResult(coverLetter, variant, previousVariants);
    }

    @Override
    @Transactional
    public List<CoverLetterVariant> deleteByGermanyResumeIdentity(UUID profileId, UUID jobId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        lockIdentity(profileId, jobId);
        List<CoverLetterVariant> removed = jdbc.sql("""
                        SELECT variant.id, variant.cover_letter_id, variant.format, variant.language,
                               variant.document_id, variant.file_path, variant.subject, variant.salutation,
                               variant.closing, variant.signature, variant.created_at, variant.updated_at
                        FROM cover_letter.cover_letter_variants variant
                        JOIN cover_letter.cover_letters parent ON parent.id = variant.cover_letter_id
                        WHERE parent.profile_id = :profileId
                          AND parent.job_id = :jobId
                        ORDER BY variant.format, variant.language
                        """)
                .param("profileId", profileId)
                .param("jobId", jobId)
                .query(this::mapVariant)
                .list();
        jdbc.sql("""
                        DELETE FROM cover_letter.cover_letters
                        WHERE profile_id = :profileId AND job_id = :jobId
                        """)
                .param("profileId", profileId)
                .param("jobId", jobId)
                .update();
        return List.copyOf(removed);
    }

    private void batchParagraphs(List<CoverLetterParagraph> paragraphs) {
        if (paragraphs.isEmpty()) {
            return;
        }
        List<SqlParameterSource> parameters = paragraphs.stream()
                .map(paragraph -> (SqlParameterSource) paragraphParameters(paragraph))
                .toList();
        namedJdbc.batchUpdate("""
                INSERT INTO cover_letter.cover_letter_paragraphs (id, variant_id, display_order, text)
                VALUES (:id, :variantId, :displayOrder, :text)
                """, parameters.toArray(SqlParameterSource[]::new));
    }

    private void lockIdentity(UUID profileId, UUID jobId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", ResumeRepository.germanyApplicationLockKey(profileId, jobId))
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private void validateCurrentSources(CoverLetter coverLetter) {
        boolean current = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM resume.resumes resume
                            JOIN profile.profiles profile ON profile.id = resume.profile_id
                            JOIN job_schema.jobs job ON job.id = resume.job_id
                            WHERE resume.id = :resumeId
                              AND resume.profile_id = :profileId
                              AND resume.job_id = :jobId
                              AND resume.format = 'germany'
                              AND resume.updated_at = :resumeRevision
                              AND profile.updated_at = :profileRevision
                              AND job.updated_at = :jobRevision
                        )
                        """)
                .param("resumeId", coverLetter.resumeId())
                .param("profileId", coverLetter.profileId())
                .param("jobId", coverLetter.jobId())
                .param("resumeRevision", Timestamp.from(coverLetter.resumeRevision()))
                .param("profileRevision", Timestamp.from(coverLetter.profileRevision()))
                .param("jobRevision", Timestamp.from(coverLetter.jobRevision()))
                .query(Boolean.class)
                .single();
        if (!current) {
            throw new ApplicationException(
                    ApplicationErrorCode.CONFLICT,
                    "Cover-letter sources changed during generation",
                    java.util.Map.of("field", "resumeId", "reason", "profile, job, or resume revision is stale"),
                    null
            );
        }
    }

    private static String parentSelect() {
        return """
                SELECT id, profile_id, job_id, resume_id, profile_revision, job_revision, resume_revision,
                       created_at, updated_at
                FROM cover_letter.cover_letters
                """;
    }

    private CoverLetter mapCoverLetter(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CoverLetter(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getObject("job_id", UUID.class),
                resultSet.getObject("resume_id", UUID.class),
                resultSet.getTimestamp("profile_revision").toInstant(),
                resultSet.getTimestamp("job_revision").toInstant(),
                resultSet.getTimestamp("resume_revision").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private CoverLetterVariant mapVariant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CoverLetterVariant(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("cover_letter_id", UUID.class),
                resultSet.getString("format"),
                resultSet.getString("language"),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("file_path"),
                resultSet.getString("subject"),
                resultSet.getString("salutation"),
                resultSet.getString("closing"),
                resultSet.getString("signature"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static MapSqlParameterSource variantParameters(CoverLetterVariant variant) {
        return new MapSqlParameterSource()
                .addValue("id", variant.id())
                .addValue("coverLetterId", variant.coverLetterId())
                .addValue("format", variant.format())
                .addValue("language", variant.language())
                .addValue("documentId", variant.documentId())
                .addValue("filePath", variant.filePath())
                .addValue("subject", variant.subject())
                .addValue("salutation", variant.salutation())
                .addValue("closing", variant.closing())
                .addValue("signature", variant.signature())
                .addValue("createdAt", Timestamp.from(variant.createdAt()))
                .addValue("updatedAt", Timestamp.from(variant.updatedAt()));
    }

    private static MapSqlParameterSource paragraphParameters(CoverLetterParagraph paragraph) {
        return new MapSqlParameterSource()
                .addValue("id", paragraph.id())
                .addValue("variantId", paragraph.variantId())
                .addValue("displayOrder", paragraph.displayOrder())
                .addValue("text", paragraph.text());
    }

}
