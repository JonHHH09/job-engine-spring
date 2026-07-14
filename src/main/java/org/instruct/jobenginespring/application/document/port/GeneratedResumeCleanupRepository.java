package org.instruct.jobenginespring.application.document.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable queue for generated-resume filesystem cleanup. */
public interface GeneratedResumeCleanupRepository {

    UUID enqueue(String filePath, Instant createdAt);

    List<UUID> findDueTaskIds(Instant now, int limit);

    Optional<String> claim(UUID taskId, Instant now, Instant leaseUntil);

    void markCompleted(UUID taskId, Instant completedAt);

    void markPending(UUID taskId, Instant nextAttemptAt, String failureType);

    CleanupQueueSnapshot readQueueSnapshot(
            Instant now,
            int repeatedFailureAttemptThreshold,
            Instant completedRetentionCutoff
    );

    int deleteCompletedBefore(Instant cutoff, int limit);

    /** Sanitized aggregate state; file paths and failure details never cross this boundary. */
    record CleanupQueueSnapshot(
            long pendingCount,
            long processingCount,
            long expiredCompletedCount,
            Instant oldestDueAt,
            boolean repeatedFailure
    ) {
        public CleanupQueueSnapshot {
            if (pendingCount < 0 || processingCount < 0 || expiredCompletedCount < 0) {
                throw new IllegalArgumentException("cleanup queue counts must not be negative");
            }
        }
    }
}
