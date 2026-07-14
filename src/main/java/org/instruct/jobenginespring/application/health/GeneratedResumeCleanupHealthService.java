package org.instruct.jobenginespring.application.health;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Reports only aggregate cleanup-queue state suitable for MCP clients and operators. */
@Service
public final class GeneratedResumeCleanupHealthService {

    private final GeneratedResumeCleanupRepository cleanupRepository;
    private final Duration oldestDueDegradedThreshold;
    private final int repeatedFailureAttemptThreshold;
    private final Clock clock;

    @Autowired
    public GeneratedResumeCleanupHealthService(
            GeneratedResumeCleanupRepository cleanupRepository,
            @Value("${job-engine.pdf-generation.cleanup-health-oldest-due-threshold:PT5M}")
            Duration oldestDueDegradedThreshold,
            @Value("${job-engine.pdf-generation.cleanup-health-repeated-failure-attempts:3}")
            int repeatedFailureAttemptThreshold
    ) {
        this(cleanupRepository, oldestDueDegradedThreshold, repeatedFailureAttemptThreshold, Clock.systemUTC());
    }

    GeneratedResumeCleanupHealthService(
            GeneratedResumeCleanupRepository cleanupRepository,
            Duration oldestDueDegradedThreshold,
            int repeatedFailureAttemptThreshold,
            Clock clock
    ) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
        this.oldestDueDegradedThreshold = Objects.requireNonNull(
                oldestDueDegradedThreshold, "oldestDueDegradedThreshold must not be null"
        );
        if (oldestDueDegradedThreshold.isNegative() || oldestDueDegradedThreshold.isZero()) {
            throw new IllegalArgumentException("oldestDueDegradedThreshold must be positive");
        }
        if (repeatedFailureAttemptThreshold < 1) {
            throw new IllegalArgumentException("repeatedFailureAttemptThreshold must be positive");
        }
        this.repeatedFailureAttemptThreshold = repeatedFailureAttemptThreshold;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CleanupHealthReport checkHealth() {
        var now = clock.instant();
        try {
            var snapshot = cleanupRepository.readQueueSnapshot(now, repeatedFailureAttemptThreshold);
            long oldestDueAgeSeconds = snapshot.oldestDueAt() == null
                    ? 0
                    : Math.max(0, Duration.between(snapshot.oldestDueAt(), now).toSeconds());
            var status = snapshot.repeatedFailure()
                    || oldestDueAgeSeconds >= oldestDueDegradedThreshold.toSeconds()
                    ? CleanupHealthStatus.DEGRADED
                    : CleanupHealthStatus.HEALTHY;
            return new CleanupHealthReport(
                    status,
                    snapshot.pendingCount(),
                    snapshot.processingCount(),
                    oldestDueAgeSeconds,
                    snapshot.repeatedFailure()
            );
        } catch (RuntimeException exception) {
            return new CleanupHealthReport(CleanupHealthStatus.UNKNOWN, 0, 0, 0, false);
        }
    }

    public enum CleanupHealthStatus {
        HEALTHY,
        DEGRADED,
        UNKNOWN
    }

    public record CleanupHealthReport(
            CleanupHealthStatus status,
            long pendingCount,
            long processingCount,
            long oldestDueAgeSeconds,
            boolean repeatedFailure
    ) {
        public CleanupHealthReport {
            Objects.requireNonNull(status, "status must not be null");
            if (pendingCount < 0 || processingCount < 0 || oldestDueAgeSeconds < 0) {
                throw new IllegalArgumentException("cleanup health metrics must not be negative");
            }
        }
    }
}
