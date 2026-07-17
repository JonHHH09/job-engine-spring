package org.instruct.jobenginespring.adapter.out.postgres.resume;

import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.instruct.jobenginespring.domain.resume.ResumeEntry;
import org.instruct.jobenginespring.domain.resume.ResumeEntryBullet;
import org.instruct.jobenginespring.domain.resume.ResumeSection;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.profile.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresResumeRepository implements ResumeRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations namedJdbc;

    public PostgresResumeRepository(NamedParameterJdbcOperations namedJdbc) {
        this.namedJdbc = Objects.requireNonNull(namedJdbc, "namedJdbc must not be null");
        this.jdbc = JdbcClient.create(namedJdbc);
    }

    @Override
    public Optional<Resume> findByProfileJobFormat(UUID profileId, UUID jobId, String format) {
        return jdbc.sql("""
                        SELECT id, profile_id, job_id, format, profile_revision, job_revision, created_at, updated_at
                        FROM resume.resumes
                        WHERE profile_id = :profileId
                          AND job_id = :jobId
                          AND format = :format
                        """)
                .param("profileId", profileId)
                .param("jobId", jobId)
                .param("format", format)
                .query(this::mapResume)
                .optional();
    }

    @Override
    public Optional<Resume> findById(UUID resumeId) {
        return jdbc.sql("""
                        SELECT id, profile_id, job_id, format, profile_revision, job_revision, created_at, updated_at
                        FROM resume.resumes
                        WHERE id = :resumeId
                        """)
                .param("resumeId", resumeId)
                .query(this::mapResume)
                .optional();
    }

    @Override
    public List<ResumeVariant> findVariants(UUID resumeId) {
        return jdbc.sql("""
                        SELECT id, resume_id, language, document_id, file_path, created_at, updated_at
                        FROM resume.resume_variants
                        WHERE resume_id = :resumeId
                        ORDER BY language
                        """)
                .param("resumeId", resumeId)
                .query(this::mapVariant)
                .list();
    }

    @Override
    @Transactional
    public ReplaceResult replaceGermanyResume(ResumeAggregateWrite write) {
        ResumeAggregateWrite safe = Objects.requireNonNull(write, "write must not be null");
        Resume resume = safe.resume();
        List<ResumeVariant> previousVariants = List.of();

        // Serialize concurrent replacements for the same profile/job/format pair so two
        // generate_german_tailored_resume calls cannot both pass the existence lookup and then
        // race on the unique (profile_id, job_id, format) constraint after PDFs were stored.
        lockGermanyResume(resume.profileId(), resume.jobId(), resume.format());

        Optional<Resume> existing = findByProfileJobFormat(resume.profileId(), resume.jobId(), resume.format());
        if (existing.isPresent()) {
            previousVariants = findVariants(existing.get().id());
            jdbc.sql("DELETE FROM resume.resumes WHERE id = :id")
                    .param("id", existing.get().id())
                    .update();
        }

        jdbc.sql("""
                        INSERT INTO resume.resumes (
                            id, profile_id, job_id, format, profile_revision, job_revision, created_at, updated_at
                        ) VALUES (
                            :id, :profileId, :jobId, :format, :profileRevision, :jobRevision, :createdAt, :updatedAt
                        )
                        """)
                .param("id", resume.id())
                .param("profileId", resume.profileId())
                .param("jobId", resume.jobId())
                .param("format", resume.format())
                .param("profileRevision", Timestamp.from(resume.profileRevision()))
                .param("jobRevision", Timestamp.from(resume.jobRevision()))
                .param("createdAt", Timestamp.from(resume.createdAt()))
                .param("updatedAt", Timestamp.from(resume.updatedAt()))
                .update();

        batchInsertNestedContent(safe.variants());
        List<ResumeVariant> savedVariants = safe.variants().stream().map(VariantWrite::variant).toList();

        return new ReplaceResult(resume, List.copyOf(savedVariants), previousVariants);
    }

    void batchInsertNestedContent(List<VariantWrite> variants) {
        batchUpdate("""
                INSERT INTO resume.resume_variants (
                    id, resume_id, language, document_id, file_path, created_at, updated_at
                ) VALUES (
                    :id, :resumeId, :language, :documentId, :filePath, :createdAt, :updatedAt
                )
                """, variants.stream().map(VariantWrite::variant).map(PostgresResumeRepository::variantParameters).toList());

        List<SectionWrite> sections = variants.stream().flatMap(variant -> variant.sections().stream()).toList();
        batchUpdate("""
                INSERT INTO resume.resume_sections (id, variant_id, section_type, title, display_order)
                VALUES (:id, :variantId, :sectionType, :title, :displayOrder)
                """, sections.stream().map(SectionWrite::section).map(PostgresResumeRepository::sectionParameters).toList());

        List<EntryWrite> entries = sections.stream().flatMap(section -> section.entries().stream()).toList();
        batchUpdate("""
                INSERT INTO resume.resume_entries (
                    id, section_id, entry_type, display_order, title, organization, location,
                    start_date, end_date, metadata
                ) VALUES (
                    :id, :sectionId, :entryType, :displayOrder, :title, :organization, :location,
                    :startDate, :endDate, :metadata
                )
                """, entries.stream().map(EntryWrite::entry).map(PostgresResumeRepository::entryParameters).toList());

        List<ResumeEntryBullet> bullets = entries.stream().flatMap(entry -> entry.bullets().stream()).toList();
        batchUpdate("""
                INSERT INTO resume.resume_entry_bullets (id, entry_id, display_order, text)
                VALUES (:id, :entryId, :displayOrder, :text)
                """, bullets.stream().map(PostgresResumeRepository::bulletParameters).toList());
    }

    private void batchUpdate(String sql, List<SqlParameterSource> parameters) {
        if (!parameters.isEmpty()) {
            namedJdbc.batchUpdate(sql, parameters.toArray(SqlParameterSource[]::new));
        }
    }

    private static SqlParameterSource variantParameters(ResumeVariant variant) {
        return new MapSqlParameterSource()
                .addValue("id", variant.id()).addValue("resumeId", variant.resumeId())
                .addValue("language", variant.language()).addValue("documentId", variant.documentId())
                .addValue("filePath", variant.filePath()).addValue("createdAt", Timestamp.from(variant.createdAt()))
                .addValue("updatedAt", Timestamp.from(variant.updatedAt()));
    }

    private static SqlParameterSource sectionParameters(ResumeSection section) {
        return new MapSqlParameterSource()
                .addValue("id", section.id()).addValue("variantId", section.variantId())
                .addValue("sectionType", section.sectionType()).addValue("title", section.title())
                .addValue("displayOrder", section.displayOrder());
    }

    private static SqlParameterSource entryParameters(ResumeEntry entry) {
        return new MapSqlParameterSource()
                .addValue("id", entry.id()).addValue("sectionId", entry.sectionId())
                .addValue("entryType", entry.entryType()).addValue("displayOrder", entry.displayOrder())
                .addValue("title", entry.title()).addValue("organization", entry.organization())
                .addValue("location", entry.location())
                .addValue("startDate", entry.startDate() == null ? null : Date.valueOf(entry.startDate()))
                .addValue("endDate", entry.endDate() == null ? null : Date.valueOf(entry.endDate()))
                .addValue("metadata", entry.metadata());
    }

    private static SqlParameterSource bulletParameters(ResumeEntryBullet bullet) {
        return new MapSqlParameterSource()
                .addValue("id", bullet.id()).addValue("entryId", bullet.entryId())
                .addValue("displayOrder", bullet.displayOrder()).addValue("text", bullet.text());
    }

    private void lockGermanyResume(UUID profileId, UUID jobId, String format) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", ResumeRepository.germanyApplicationLockKey(profileId, jobId))
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private Resume mapResume(ResultSet rs, int rowNum) throws SQLException {
        return new Resume(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("profile_id"),
                (UUID) rs.getObject("job_id"),
                rs.getString("format"),
                rs.getTimestamp("profile_revision").toInstant(),
                rs.getTimestamp("job_revision").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private ResumeVariant mapVariant(ResultSet rs, int rowNum) throws SQLException {
        return new ResumeVariant(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("resume_id"),
                rs.getString("language"),
                (UUID) rs.getObject("document_id"),
                rs.getString("file_path"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
