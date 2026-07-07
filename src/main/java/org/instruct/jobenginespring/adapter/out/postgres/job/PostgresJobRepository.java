package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobLinkIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.job.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresJobRepository implements JobRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations namedJdbc;

    public PostgresJobRepository(NamedParameterJdbcOperations namedJdbc) {
        this.namedJdbc = Objects.requireNonNull(namedJdbc, "namedJdbc must not be null");
        this.jdbc = JdbcClient.create(namedJdbc);
    }

    @Override
    public List<JobPosting> listJobs() {
        return jdbc.sql("""
                        SELECT id, source_method, source_label, title, company, location, description,
                               experience_requirement, employment_type, seniority, posted_at,
                               canonical_fingerprint, created_at, updated_at
                        FROM job_schema.jobs
                        ORDER BY posted_at DESC NULLS LAST, created_at DESC, title, id
                        """)
                .query(this::mapJob)
                .list();
    }

    @Override
    public Optional<JobAggregate> findJobAggregate(UUID jobId) {
        return findJobById(jobId).map(job -> new JobAggregate(
                job,
                listSkills(jobId),
                findLinkIngestion(jobId).orElse(null),
                findTextIngestion(jobId).orElse(null)
        ));
    }

    @Override
    public Optional<JobAggregate> findByCanonicalFingerprint(String canonicalFingerprint) {
        return jdbc.sql("""
                        SELECT id
                        FROM job_schema.jobs
                        WHERE canonical_fingerprint = :canonicalFingerprint
                        """)
                .param("canonicalFingerprint", canonicalFingerprint)
                .query((resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
                .optional()
                .flatMap(this::findJobAggregate);
    }

    @Override
    public Optional<JobAggregate> findByNormalizedSourceUrl(String normalizedUrl) {
        return jdbc.sql("""
                        SELECT job_id
                        FROM job_schema.job_link_ingestions
                        WHERE normalized_url = :normalizedUrl
                        """)
                .param("normalizedUrl", normalizedUrl)
                .query((resultSet, rowNumber) -> resultSet.getObject("job_id", UUID.class))
                .optional()
                .flatMap(this::findJobAggregate);
    }

    @Override
    public Optional<JobAggregate> findByInputTextHash(String inputTextHash) {
        return jdbc.sql("""
                        SELECT job_id
                        FROM job_schema.job_text_ingestions
                        WHERE input_text_hash = :inputTextHash
                        """)
                .param("inputTextHash", inputTextHash)
                .query((resultSet, rowNumber) -> resultSet.getObject("job_id", UUID.class))
                .optional()
                .flatMap(this::findJobAggregate);
    }

    @Override
    public JobAggregate saveJobAggregate(JobAggregate aggregate) {
        JobPosting job = aggregate.job();
        int insertedJobs = jdbc.sql("""
                        INSERT INTO job_schema.jobs
                            (id, source_method, source_label, title, company, location, description,
                             experience_requirement, employment_type, seniority, posted_at,
                             canonical_fingerprint, created_at, updated_at)
                        VALUES
                            (:id, :sourceMethod, :sourceLabel, :title, :company, :location, :description,
                             :experienceRequirement, :employmentType, :seniority, :postedAt,
                             :canonicalFingerprint, :createdAt, :updatedAt)
                        ON CONFLICT ON CONSTRAINT jobs_canonical_fingerprint_unique DO NOTHING
                        """)
                .param("id", job.id())
                .param("sourceMethod", job.sourceMethod())
                .param("sourceLabel", job.sourceLabel())
                .param("title", job.title())
                .param("company", job.company())
                .param("location", job.location())
                .param("description", job.description())
                .param("experienceRequirement", job.experienceRequirement())
                .param("employmentType", job.employmentType())
                .param("seniority", job.seniority())
                .param("postedAt", timestamp(job.postedAt()))
                .param("canonicalFingerprint", job.canonicalFingerprint())
                .param("createdAt", Timestamp.from(job.createdAt()))
                .param("updatedAt", Timestamp.from(job.updatedAt()))
                .update();
        if (insertedJobs == 0) {
            return findByCanonicalFingerprint(job.canonicalFingerprint()).orElseThrow();
        }
        batchInsertSkills(aggregate.skills());
        if (!insertLink(aggregate.linkIngestion())) {
            deleteInsertedJob(job.id());
            return findByNormalizedSourceUrl(aggregate.linkIngestion().normalizedUrl()).orElseThrow();
        }
        if (!insertText(aggregate.textIngestion())) {
            deleteInsertedJob(job.id());
            return findByInputTextHash(aggregate.textIngestion().inputTextHash()).orElseThrow();
        }
        return findJobAggregate(job.id()).orElseThrow();
    }

    @Override
    public JobAggregate updateJobAggregate(JobAggregate aggregate) {
        JobPosting job = aggregate.job();
        int updatedJobs = jdbc.sql("""
                        UPDATE job_schema.jobs
                        SET source_label = :sourceLabel,
                            title = :title,
                            company = :company,
                            location = :location,
                            description = :description,
                            experience_requirement = :experienceRequirement,
                            employment_type = :employmentType,
                            seniority = :seniority,
                            posted_at = :postedAt,
                            canonical_fingerprint = :canonicalFingerprint,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("id", job.id())
                .param("sourceLabel", job.sourceLabel())
                .param("title", job.title())
                .param("company", job.company())
                .param("location", job.location())
                .param("description", job.description())
                .param("experienceRequirement", job.experienceRequirement())
                .param("employmentType", job.employmentType())
                .param("seniority", job.seniority())
                .param("postedAt", timestamp(job.postedAt()))
                .param("canonicalFingerprint", job.canonicalFingerprint())
                .param("updatedAt", Timestamp.from(job.updatedAt()))
                .update();
        if (updatedJobs == 0) {
            throw new IllegalStateException("Job disappeared during update: " + job.id());
        }
        replaceSkills(job.id(), aggregate.skills());
        return findJobAggregate(job.id()).orElseThrow();
    }

    @Override
    public boolean deleteJob(UUID jobId) {
        return jdbc.sql("DELETE FROM job_schema.jobs WHERE id = :jobId")
                .param("jobId", jobId)
                .update() > 0;
    }

    private void deleteInsertedJob(UUID jobId) {
        jdbc.sql("DELETE FROM job_schema.jobs WHERE id = :jobId")
                .param("jobId", jobId)
                .update();
    }

    private void replaceSkills(UUID jobId, List<JobSkill> skills) {
        jdbc.sql("DELETE FROM job_schema.job_skills WHERE job_id = :jobId")
                .param("jobId", jobId)
                .update();
        batchInsertSkills(skills);
    }

    private Optional<JobPosting> findJobById(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, source_method, source_label, title, company, location, description,
                               experience_requirement, employment_type, seniority, posted_at,
                               canonical_fingerprint, created_at, updated_at
                        FROM job_schema.jobs
                        WHERE id = :jobId
                        """)
                .param("jobId", jobId)
                .query(this::mapJob)
                .optional();
    }

    private List<JobSkill> listSkills(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, job_id, skill, normalized_skill, required, display_order, created_at
                        FROM job_schema.job_skills
                        WHERE job_id = :jobId
                        ORDER BY display_order, skill, id
                        """)
                .param("jobId", jobId)
                .query(mapSkill())
                .list();
    }

    private Optional<JobLinkIngestion> findLinkIngestion(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, job_id, url, normalized_url, fetched_at, http_status, source_title, created_at
                        FROM job_schema.job_link_ingestions
                        WHERE job_id = :jobId
                        """)
                .param("jobId", jobId)
                .query(this::mapLink)
                .optional();
    }

    private Optional<JobTextIngestion> findTextIngestion(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, job_id, source_label, input_text_hash, created_at
                        FROM job_schema.job_text_ingestions
                        WHERE job_id = :jobId
                        """)
                .param("jobId", jobId)
                .query(this::mapText)
                .optional();
    }

    private void batchInsertSkills(List<JobSkill> skills) {
        if (skills.isEmpty()) {
            return;
        }
        namedJdbc.batchUpdate("""
                        INSERT INTO job_schema.job_skills
                            (id, job_id, skill, normalized_skill, required, display_order, created_at)
                        VALUES (:id, :jobId, :skill, :normalizedSkill, :required, :displayOrder, :createdAt)
                        """,
                skills.stream().map(PostgresJobRepository::skillParameters).toArray(SqlParameterSource[]::new));
    }

    private boolean insertLink(JobLinkIngestion link) {
        if (link == null) {
            return true;
        }
        return jdbc.sql("""
                        INSERT INTO job_schema.job_link_ingestions
                            (id, job_id, url, normalized_url, fetched_at, http_status, source_title, created_at)
                        VALUES (:id, :jobId, :url, :normalizedUrl, :fetchedAt, :httpStatus, :sourceTitle, :createdAt)
                        ON CONFLICT ON CONSTRAINT job_link_ingestions_normalized_url_unique DO NOTHING
                        """)
                .param("id", link.id())
                .param("jobId", link.jobId())
                .param("url", link.url())
                .param("normalizedUrl", link.normalizedUrl())
                .param("fetchedAt", Timestamp.from(link.fetchedAt()))
                .param("httpStatus", link.httpStatus())
                .param("sourceTitle", link.sourceTitle())
                .param("createdAt", Timestamp.from(link.createdAt()))
                .update() > 0;
    }

    private boolean insertText(JobTextIngestion text) {
        if (text == null) {
            return true;
        }
        return jdbc.sql("""
                        INSERT INTO job_schema.job_text_ingestions
                            (id, job_id, source_label, input_text_hash, created_at)
                        VALUES (:id, :jobId, :sourceLabel, :inputTextHash, :createdAt)
                        ON CONFLICT ON CONSTRAINT job_text_ingestions_hash_unique DO NOTHING
                        """)
                .param("id", text.id())
                .param("jobId", text.jobId())
                .param("sourceLabel", text.sourceLabel())
                .param("inputTextHash", text.inputTextHash())
                .param("createdAt", Timestamp.from(text.createdAt()))
                .update() > 0;
    }

    private JobPosting mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobPosting(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_method"),
                resultSet.getString("source_label"),
                resultSet.getString("title"),
                resultSet.getString("company"),
                resultSet.getString("location"),
                resultSet.getString("description"),
                resultSet.getString("experience_requirement"),
                resultSet.getString("employment_type"),
                resultSet.getString("seniority"),
                instantOrNull(resultSet, "posted_at"),
                resultSet.getString("canonical_fingerprint"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static RowMapper<JobSkill> mapSkill() {
        return (resultSet, rowNumber) -> new JobSkill(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("job_id", UUID.class),
                resultSet.getString("skill"),
                resultSet.getString("normalized_skill"),
                resultSet.getBoolean("required"),
                resultSet.getInt("display_order"),
                instant(resultSet, "created_at")
        );
    }

    private JobLinkIngestion mapLink(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobLinkIngestion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("job_id", UUID.class),
                resultSet.getString("url"),
                resultSet.getString("normalized_url"),
                instant(resultSet, "fetched_at"),
                resultSet.getObject("http_status", Integer.class),
                resultSet.getString("source_title"),
                instant(resultSet, "created_at")
        );
    }

    private JobTextIngestion mapText(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobTextIngestion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("job_id", UUID.class),
                resultSet.getString("source_label"),
                resultSet.getString("input_text_hash"),
                instant(resultSet, "created_at")
        );
    }

    private static SqlParameterSource skillParameters(JobSkill skill) {
        return new MapSqlParameterSource()
                .addValue("id", skill.id())
                .addValue("jobId", skill.jobId())
                .addValue("skill", skill.skill())
                .addValue("normalizedSkill", skill.normalizedSkill())
                .addValue("required", skill.required())
                .addValue("displayOrder", skill.displayOrder())
                .addValue("createdAt", Timestamp.from(skill.createdAt()));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }
}
