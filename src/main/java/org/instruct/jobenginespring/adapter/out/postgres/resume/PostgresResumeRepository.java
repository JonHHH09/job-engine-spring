package org.instruct.jobenginespring.adapter.out.postgres.resume;

import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.instruct.jobenginespring.domain.resume.ResumeEntry;
import org.instruct.jobenginespring.domain.resume.ResumeEntryBullet;
import org.instruct.jobenginespring.domain.resume.ResumeSection;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.profile.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresResumeRepository implements ResumeRepository {

    private final JdbcClient jdbc;

    public PostgresResumeRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
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

        List<ResumeVariant> savedVariants = new ArrayList<>();
        for (VariantWrite variantWrite : safe.variants()) {
            ResumeVariant variant = variantWrite.variant();
            jdbc.sql("""
                            INSERT INTO resume.resume_variants (
                                id, resume_id, language, document_id, file_path, created_at, updated_at
                            ) VALUES (
                                :id, :resumeId, :language, :documentId, :filePath, :createdAt, :updatedAt
                            )
                            """)
                    .param("id", variant.id())
                    .param("resumeId", variant.resumeId())
                    .param("language", variant.language())
                    .param("documentId", variant.documentId())
                    .param("filePath", variant.filePath())
                    .param("createdAt", Timestamp.from(variant.createdAt()))
                    .param("updatedAt", Timestamp.from(variant.updatedAt()))
                    .update();
            savedVariants.add(variant);

            for (SectionWrite sectionWrite : variantWrite.sections()) {
                ResumeSection section = sectionWrite.section();
                jdbc.sql("""
                                INSERT INTO resume.resume_sections (
                                    id, variant_id, section_type, title, display_order
                                ) VALUES (
                                    :id, :variantId, :sectionType, :title, :displayOrder
                                )
                                """)
                        .param("id", section.id())
                        .param("variantId", section.variantId())
                        .param("sectionType", section.sectionType())
                        .param("title", section.title())
                        .param("displayOrder", section.displayOrder())
                        .update();

                for (EntryWrite entryWrite : sectionWrite.entries()) {
                    ResumeEntry entry = entryWrite.entry();
                    jdbc.sql("""
                                    INSERT INTO resume.resume_entries (
                                        id, section_id, entry_type, display_order, title, organization, location,
                                        start_date, end_date, metadata
                                    ) VALUES (
                                        :id, :sectionId, :entryType, :displayOrder, :title, :organization, :location,
                                        :startDate, :endDate, :metadata
                                    )
                                    """)
                            .param("id", entry.id())
                            .param("sectionId", entry.sectionId())
                            .param("entryType", entry.entryType())
                            .param("displayOrder", entry.displayOrder())
                            .param("title", entry.title())
                            .param("organization", entry.organization())
                            .param("location", entry.location())
                            .param("startDate", entry.startDate() == null ? null : Date.valueOf(entry.startDate()))
                            .param("endDate", entry.endDate() == null ? null : Date.valueOf(entry.endDate()))
                            .param("metadata", entry.metadata())
                            .update();

                    for (ResumeEntryBullet bullet : entryWrite.bullets()) {
                        jdbc.sql("""
                                        INSERT INTO resume.resume_entry_bullets (
                                            id, entry_id, display_order, text
                                        ) VALUES (
                                            :id, :entryId, :displayOrder, :text
                                        )
                                        """)
                                .param("id", bullet.id())
                                .param("entryId", bullet.entryId())
                                .param("displayOrder", bullet.displayOrder())
                                .param("text", bullet.text())
                                .update();
                    }
                }
            }
        }

        return new ReplaceResult(resume, List.copyOf(savedVariants), previousVariants);
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
