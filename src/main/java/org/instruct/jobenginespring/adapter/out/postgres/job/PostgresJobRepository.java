package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.application.pagination.SearchCandidates;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobLinkIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public Page<JobPosting> listJobs(PageRequest request) {
        var rows = jdbc.sql("""
                        WITH anchor AS (
                            SELECT COALESCE(posted_at, '-infinity'::timestamptz) AS posted_at,
                                   created_at, title, id
                            FROM job_schema.jobs
                            WHERE id = :cursor
                        )
                        SELECT job.id, job.source_method, job.source_label, job.title, job.company, job.location,
                               job.description, job.experience_requirement, job.employment_type, job.seniority,
                               job.posted_at, job.canonical_fingerprint, job.created_at, job.updated_at
                        FROM job_schema.jobs job
                        LEFT JOIN anchor ON true
                        WHERE CAST(:cursor AS uuid) IS NULL
                           OR COALESCE(job.posted_at, '-infinity'::timestamptz) < anchor.posted_at
                           OR (COALESCE(job.posted_at, '-infinity'::timestamptz) = anchor.posted_at
                               AND job.created_at < anchor.created_at)
                           OR (COALESCE(job.posted_at, '-infinity'::timestamptz) = anchor.posted_at
                               AND job.created_at = anchor.created_at AND job.title > anchor.title)
                           OR (COALESCE(job.posted_at, '-infinity'::timestamptz) = anchor.posted_at
                               AND job.created_at = anchor.created_at AND job.title = anchor.title AND job.id > anchor.id)
                        ORDER BY job.posted_at DESC NULLS LAST, job.created_at DESC, job.title, job.id
                        LIMIT :fetchLimit
                        """)
                .param("cursor", request.cursor())
                .param("fetchLimit", request.limit() + 1)
                .query(this::mapJob)
                .list();
        boolean hasMore = rows.size() > request.limit();
        var items = rows.stream().limit(request.limit()).toList();
        return new Page<>(items, hasMore ? items.getLast().id() : null);
    }

    @Override
    public List<JobAggregate> listJobAggregates() {
        List<JobPosting> jobs = listJobs();
        return aggregatesForJobs(jobs);
    }

    @Override
    public SearchCandidates<JobAggregate> searchJobCandidates(List<String> queryTokens, int limit) {
        var ranked = namedJdbc.query("""
                        WITH query_tokens AS (
                            SELECT token FROM unnest(CAST(:queryTokens AS text[])) AS token
                        ), field_values AS (
                            SELECT id AS job_id, title AS value, 8 AS weight FROM job_schema.jobs
                            UNION ALL SELECT id, company, 5 FROM job_schema.jobs
                            UNION ALL SELECT id, location, 3 FROM job_schema.jobs
                            UNION ALL SELECT id, description, 3 FROM job_schema.jobs
                            UNION ALL SELECT id, experience_requirement, 4 FROM job_schema.jobs
                            UNION ALL SELECT id, employment_type, 2 FROM job_schema.jobs
                            UNION ALL SELECT id, seniority, 2 FROM job_schema.jobs
                            UNION ALL SELECT job_id, skill, 7 FROM job_schema.job_skills
                        ), scored AS (
                            SELECT job_id, SUM(weight * (
                                SELECT COUNT(*) FROM query_tokens query_token
                                WHERE EXISTS (
                                    SELECT 1
                                    FROM regexp_split_to_table(lower(COALESCE(value, '')), '[^a-z0-9+#.]+') text_token
                                    WHERE text_token <> '' AND (
                                        text_token = query_token.token
                                        OR starts_with(text_token, query_token.token)
                                        OR starts_with(query_token.token, text_token)
                                    )
                                )
                            ))::integer AS score
                            FROM field_values
                            GROUP BY job_id
                        ), matching AS (
                            SELECT job.*, scored.score, COUNT(*) OVER () AS total_matches
                            FROM scored
                            JOIN job_schema.jobs job ON job.id = scored.job_id
                            WHERE scored.score > 0
                            ORDER BY scored.score DESC, job.title, job.id
                            LIMIT :limit
                        )
                        SELECT * FROM matching ORDER BY score DESC, title, id
                        """, new MapSqlParameterSource()
                        .addValue("queryTokens", queryTokens.toArray(String[]::new))
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> new RankedJob(
                        mapJob(resultSet, rowNumber), resultSet.getInt("total_matches")));
        if (ranked.isEmpty()) {
            return new SearchCandidates<>(0, List.of());
        }
        var jobs = ranked.stream().map(RankedJob::job).toList();
        return new SearchCandidates<>(ranked.getFirst().totalMatches(), aggregatesForJobs(jobs));
    }

    @Override
    public Optional<JobAggregate> findJobAggregate(UUID jobId) {
        return findJobById(jobId)
                .flatMap(job -> aggregatesForJobs(List.of(job)).stream().findFirst());
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
        boolean inserted = insertJobWithMatchingProvenance(aggregate);
        if (!inserted) {
            if (aggregate.linkIngestion() != null) {
                return findByNormalizedSourceUrl(aggregate.linkIngestion().normalizedUrl())
                        .or(() -> findByCanonicalFingerprint(job.canonicalFingerprint()))
                        .orElseThrow();
            }
            return findByInputTextHash(aggregate.textIngestion().inputTextHash())
                    .or(() -> findByCanonicalFingerprint(job.canonicalFingerprint()))
                    .orElseThrow();
        }
        batchInsertSkills(aggregate.skills());
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

    private boolean insertJobWithMatchingProvenance(JobAggregate aggregate) {
        return aggregate.linkIngestion() != null
                ? insertJobWithLinkProvenance(aggregate.job(), aggregate.linkIngestion())
                : insertJobWithTextProvenance(aggregate.job(), aggregate.textIngestion());
    }

    private boolean insertJobWithLinkProvenance(JobPosting job, JobLinkIngestion link) {
        Integer inserted = namedJdbc.queryForObject("""
                        WITH existing_source AS (
                            SELECT job_id
                            FROM job_schema.job_link_ingestions
                            WHERE normalized_url = :normalizedUrl
                        ),
                        inserted_job AS (
                            INSERT INTO job_schema.jobs
                                (id, source_method, source_label, title, company, location, description,
                                 experience_requirement, employment_type, seniority, posted_at,
                                 canonical_fingerprint, created_at, updated_at)
                            SELECT
                                :id, :sourceMethod, :sourceLabel, :title, :company, :location, :description,
                                :experienceRequirement, :employmentType, :seniority, :postedAt,
                                :canonicalFingerprint, :createdAt, :updatedAt
                            WHERE NOT EXISTS (SELECT 1 FROM existing_source)
                            ON CONFLICT ON CONSTRAINT jobs_canonical_fingerprint_unique DO NOTHING
                            RETURNING id
                        ),
                        inserted_link AS (
                            INSERT INTO job_schema.job_link_ingestions
                                (id, job_id, url, normalized_url, fetched_at, http_status, source_title, created_at)
                            SELECT
                                :linkId, :id, :url, :normalizedUrl, :fetchedAt, :httpStatus, :sourceTitle, :linkCreatedAt
                            FROM inserted_job
                            ON CONFLICT ON CONSTRAINT job_link_ingestions_normalized_url_unique DO NOTHING
                            RETURNING job_id
                        ),
                        rolled_back_job AS (
                            DELETE FROM job_schema.jobs
                            WHERE id IN (SELECT id FROM inserted_job)
                              AND NOT EXISTS (SELECT 1 FROM inserted_link)
                            RETURNING id
                        )
                        SELECT count(*) FROM inserted_link
                        """,
                jobParameters(job)
                        .addValue("linkId", link.id())
                        .addValue("url", link.url())
                        .addValue("normalizedUrl", link.normalizedUrl())
                        .addValue("fetchedAt", Timestamp.from(link.fetchedAt()))
                        .addValue("httpStatus", link.httpStatus())
                        .addValue("sourceTitle", link.sourceTitle())
                        .addValue("linkCreatedAt", Timestamp.from(link.createdAt())),
                Integer.class);
        return Objects.requireNonNull(inserted, "insert count must not be null") > 0;
    }

    private boolean insertJobWithTextProvenance(JobPosting job, JobTextIngestion text) {
        Integer inserted = namedJdbc.queryForObject("""
                        WITH existing_source AS (
                            SELECT job_id
                            FROM job_schema.job_text_ingestions
                            WHERE input_text_hash = :inputTextHash
                        ),
                        inserted_job AS (
                            INSERT INTO job_schema.jobs
                                (id, source_method, source_label, title, company, location, description,
                                 experience_requirement, employment_type, seniority, posted_at,
                                 canonical_fingerprint, created_at, updated_at)
                            SELECT
                                :id, :sourceMethod, :sourceLabel, :title, :company, :location, :description,
                                :experienceRequirement, :employmentType, :seniority, :postedAt,
                                :canonicalFingerprint, :createdAt, :updatedAt
                            WHERE NOT EXISTS (SELECT 1 FROM existing_source)
                            ON CONFLICT ON CONSTRAINT jobs_canonical_fingerprint_unique DO NOTHING
                            RETURNING id
                        ),
                        inserted_text AS (
                            INSERT INTO job_schema.job_text_ingestions
                                (id, job_id, source_label, input_text_hash, created_at)
                            SELECT
                                :textId, :id, :textSourceLabel, :inputTextHash, :textCreatedAt
                            FROM inserted_job
                            ON CONFLICT ON CONSTRAINT job_text_ingestions_hash_unique DO NOTHING
                            RETURNING job_id
                        ),
                        rolled_back_job AS (
                            DELETE FROM job_schema.jobs
                            WHERE id IN (SELECT id FROM inserted_job)
                              AND NOT EXISTS (SELECT 1 FROM inserted_text)
                            RETURNING id
                        )
                        SELECT count(*) FROM inserted_text
                        """,
                jobParameters(job)
                        .addValue("textId", text.id())
                        .addValue("textSourceLabel", text.sourceLabel())
                        .addValue("inputTextHash", text.inputTextHash())
                        .addValue("textCreatedAt", Timestamp.from(text.createdAt())),
                Integer.class);
        return Objects.requireNonNull(inserted, "insert count must not be null") > 0;
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

    private List<JobAggregate> aggregatesForJobs(List<JobPosting> jobs) {
        if (jobs.isEmpty()) {
            return List.of();
        }
        List<UUID> jobIds = jobs.stream().map(JobPosting::id).toList();
        Map<UUID, List<JobSkill>> skillsByJobId = new LinkedHashMap<>();
        for (UUID jobId : jobIds) {
            skillsByJobId.put(jobId, new ArrayList<>());
        }
        namedJdbc.query("""
                        SELECT id, job_id, skill, normalized_skill, required, display_order, created_at
                        FROM job_schema.job_skills
                        WHERE job_id IN (:jobIds)
                        ORDER BY job_id, display_order, skill, id
                        """,
                new MapSqlParameterSource("jobIds", jobIds),
                (RowCallbackHandler) resultSet -> skillsByJobId.get(resultSet.getObject("job_id", UUID.class))
                        .add(mapSkill().mapRow(resultSet, 0)));
        Map<UUID, JobLinkIngestion> linksByJobId = new LinkedHashMap<>();
        namedJdbc.query("""
                        SELECT id, job_id, url, normalized_url, fetched_at, http_status, source_title, created_at
                        FROM job_schema.job_link_ingestions
                        WHERE job_id IN (:jobIds)
                        """,
                new MapSqlParameterSource("jobIds", jobIds),
                (RowCallbackHandler) resultSet -> linksByJobId.put(resultSet.getObject("job_id", UUID.class), mapLink(resultSet, 0)));
        Map<UUID, JobTextIngestion> textsByJobId = new LinkedHashMap<>();
        namedJdbc.query("""
                        SELECT id, job_id, source_label, input_text_hash, created_at
                        FROM job_schema.job_text_ingestions
                        WHERE job_id IN (:jobIds)
                        """,
                new MapSqlParameterSource("jobIds", jobIds),
                (RowCallbackHandler) resultSet -> textsByJobId.put(resultSet.getObject("job_id", UUID.class), mapText(resultSet, 0)));
        return jobs.stream()
                .map(job -> new JobAggregate(
                        job,
                        List.copyOf(skillsByJobId.getOrDefault(job.id(), List.of())),
                        linksByJobId.get(job.id()),
                        textsByJobId.get(job.id())
                ))
                .toList();
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

    private static MapSqlParameterSource jobParameters(JobPosting job) {
        return new MapSqlParameterSource()
                .addValue("id", job.id())
                .addValue("sourceMethod", job.sourceMethod())
                .addValue("sourceLabel", job.sourceLabel())
                .addValue("title", job.title())
                .addValue("company", job.company())
                .addValue("location", job.location())
                .addValue("description", job.description())
                .addValue("experienceRequirement", job.experienceRequirement())
                .addValue("employmentType", job.employmentType())
                .addValue("seniority", job.seniority())
                .addValue("postedAt", timestamp(job.postedAt()))
                .addValue("canonicalFingerprint", job.canonicalFingerprint())
                .addValue("createdAt", Timestamp.from(job.createdAt()))
                .addValue("updatedAt", Timestamp.from(job.updatedAt()));
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

    private record RankedJob(JobPosting job, int totalMatches) {
    }
}
