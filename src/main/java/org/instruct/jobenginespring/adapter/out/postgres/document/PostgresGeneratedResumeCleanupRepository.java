package org.instruct.jobenginespring.adapter.out.postgres.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.document.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresGeneratedResumeCleanupRepository implements GeneratedResumeCleanupRepository {

    private final JdbcClient jdbc;

    public PostgresGeneratedResumeCleanupRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public UUID enqueue(String filePath, Instant createdAt) {
        return enqueue(null, filePath, createdAt);
    }

    @Override
    public UUID enqueue(UUID documentId, String filePath, Instant createdAt) {
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document.generated_resume_file_cleanups (
                            id, document_id, file_path, status, attempt_count, next_attempt_at, created_at, updated_at
                        ) VALUES (
                            :id, :documentId, :filePath, 'PENDING', 0, :createdAt, :createdAt, :createdAt
                        )
                        """)
                .param("id", taskId)
                .param("documentId", documentId)
                .param("filePath", filePath)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        return taskId;
    }

    @Override
    public Optional<UUID> findDocumentId(UUID taskId) {
        return jdbc.sql("""
                        SELECT document_id
                        FROM document.generated_resume_file_cleanups
                        WHERE id = :id
                        """)
                .param("id", taskId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public List<UUID> findDueTaskIds(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id
                        FROM document.generated_resume_file_cleanups
                        WHERE status IN ('PENDING', 'PROCESSING')
                          AND next_attempt_at <= :now
                        ORDER BY next_attempt_at, created_at, id
                        LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    @Override
    public Optional<String> claim(UUID taskId, Instant now, Instant leaseUntil) {
        return jdbc.sql("""
                        UPDATE document.generated_resume_file_cleanups
                        SET status = 'PROCESSING',
                            attempt_count = attempt_count + 1,
                            next_attempt_at = :leaseUntil,
                            updated_at = :now
                        WHERE id = :id
                          AND status IN ('PENDING', 'PROCESSING')
                          AND next_attempt_at <= :now
                        RETURNING file_path
                        """)
                .param("id", taskId)
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .query(String.class)
                .optional();
    }

    @Override
    public void markCompleted(UUID taskId, Instant completedAt) {
        jdbc.sql("""
                        UPDATE document.generated_resume_file_cleanups
                        SET status = 'COMPLETED', completed_at = :completedAt, last_failure_type = NULL,
                            updated_at = :completedAt
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", taskId)
                .param("completedAt", Timestamp.from(completedAt))
                .update();
    }

    @Override
    public void markPending(UUID taskId, Instant nextAttemptAt, String failureType) {
        jdbc.sql("""
                        UPDATE document.generated_resume_file_cleanups
                        SET status = 'PENDING', next_attempt_at = :nextAttemptAt,
                            last_failure_type = :failureType, updated_at = :nextAttemptAt
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", taskId)
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("failureType", failureType)
                .update();
    }
}
