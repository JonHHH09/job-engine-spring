package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.port.JobAnalysisRunRepository;
import org.instruct.jobenginespring.domain.job.JobAnalysisRun;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.job-analysis.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresJobAnalysisRunRepository implements JobAnalysisRunRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresJobAnalysisRunRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    @Transactional
    public JobAnalysisRun save(JobAnalysisRun analysisRun) {
        jdbc.sql("""
                        INSERT INTO job_schema.job_analysis_runs
                            (id, source_type, original_url, normalized_url, fetch_status, http_status, fetched_title,
                             input_sha256, input_json, hermes_status, hermes_response_json, hermes_response_sha256,
                             validation_status, validation_errors, created_job_id, created_at, updated_at)
                        VALUES
                            (:id, :sourceType, :originalUrl, :normalizedUrl, :fetchStatus, :httpStatus, :fetchedTitle,
                             :inputSha256, CAST(:inputJson AS jsonb), :hermesStatus, CAST(:hermesResponseJson AS jsonb), :hermesResponseSha256,
                             :validationStatus, CAST(:validationErrors AS jsonb), :createdJobId, :createdAt, :updatedAt)
                        """)
                .param("id", analysisRun.id())
                .param("sourceType", analysisRun.sourceType())
                .param("originalUrl", analysisRun.originalUrl())
                .param("normalizedUrl", analysisRun.normalizedUrl())
                .param("fetchStatus", analysisRun.fetchStatus())
                .param("httpStatus", analysisRun.httpStatus())
                .param("fetchedTitle", analysisRun.fetchedTitle())
                .param("inputSha256", analysisRun.inputSha256())
                .param("inputJson", writeJson(analysisRun.inputJson()))
                .param("hermesStatus", analysisRun.hermesStatus())
                .param("hermesResponseJson", writeNullableJson(analysisRun.hermesResponseJson()))
                .param("hermesResponseSha256", analysisRun.hermesResponseSha256())
                .param("validationStatus", analysisRun.validationStatus())
                .param("validationErrors", writeJson(analysisRun.validationErrors()))
                .param("createdJobId", analysisRun.createdJobId())
                .param("createdAt", Timestamp.from(analysisRun.createdAt()))
                .param("updatedAt", Timestamp.from(analysisRun.updatedAt()))
                .update();
        return findById(analysisRun.id()).orElseThrow();
    }

    @Override
    public Optional<JobAnalysisRun> findById(UUID analysisRunId) {
        return jdbc.sql("""
                        SELECT id, source_type, original_url, normalized_url, fetch_status, http_status, fetched_title,
                               input_sha256, input_json::text AS input_json, hermes_status,
                               hermes_response_json::text AS hermes_response_json, hermes_response_sha256,
                               validation_status, validation_errors::text AS validation_errors,
                               created_job_id, created_at, updated_at
                        FROM job_schema.job_analysis_runs
                        WHERE id = :analysisRunId
                        """)
                .param("analysisRunId", analysisRunId)
                .query(this::mapAnalysisRun)
                .optional();
    }

    @Override
    @Transactional
    public JobAnalysisRun update(JobAnalysisRun analysisRun) {
        jdbc.sql("""
                        UPDATE job_schema.job_analysis_runs
                        SET fetch_status = :fetchStatus,
                            http_status = :httpStatus,
                            fetched_title = :fetchedTitle,
                            hermes_status = :hermesStatus,
                            hermes_response_json = CAST(:hermesResponseJson AS jsonb),
                            hermes_response_sha256 = :hermesResponseSha256,
                            validation_status = :validationStatus,
                            validation_errors = CAST(:validationErrors AS jsonb),
                            created_job_id = :createdJobId,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("id", analysisRun.id())
                .param("fetchStatus", analysisRun.fetchStatus())
                .param("httpStatus", analysisRun.httpStatus())
                .param("fetchedTitle", analysisRun.fetchedTitle())
                .param("hermesStatus", analysisRun.hermesStatus())
                .param("hermesResponseJson", writeNullableJson(analysisRun.hermesResponseJson()))
                .param("hermesResponseSha256", analysisRun.hermesResponseSha256())
                .param("validationStatus", analysisRun.validationStatus())
                .param("validationErrors", writeJson(analysisRun.validationErrors()))
                .param("createdJobId", analysisRun.createdJobId())
                .param("updatedAt", Timestamp.from(analysisRun.updatedAt()))
                .update();
        return findById(analysisRun.id()).orElseThrow();
    }

    private JobAnalysisRun mapAnalysisRun(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobAnalysisRun(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_type"),
                resultSet.getString("original_url"),
                resultSet.getString("normalized_url"),
                resultSet.getString("fetch_status"),
                resultSet.getObject("http_status", Integer.class),
                resultSet.getString("fetched_title"),
                resultSet.getString("input_sha256"),
                readMap(resultSet.getString("input_json")),
                resultSet.getString("hermes_status"),
                readNullableMap(resultSet.getString("hermes_response_json")),
                resultSet.getString("hermes_response_sha256"),
                resultSet.getString("validation_status"),
                readStringList(resultSet.getString("validation_errors")),
                resultSet.getObject("created_job_id", UUID.class),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException exception) {
            throw jsonFailure(exception);
        }
    }

    private Map<String, Object> readNullableMap(String json) {
        if (json == null) {
            return null;
        }
        return readMap(json);
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JacksonException exception) {
            throw jsonFailure(exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw jsonFailure(exception);
        }
    }

    private String writeNullableJson(Object value) {
        return value == null ? null : writeJson(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static ApplicationException jsonFailure(Exception exception) {
        return new ApplicationException(ApplicationErrorCode.INTERNAL_ERROR, "Job analysis JSON serialization failed", Map.of(), exception);
    }
}
