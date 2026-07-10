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
}
