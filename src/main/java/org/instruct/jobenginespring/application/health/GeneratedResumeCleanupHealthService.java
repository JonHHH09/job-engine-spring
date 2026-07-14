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
    private final Duration completedRetention;
    private final Clock clock;

    @Autowired
    public GeneratedResumeCleanupHealthService(
            GeneratedResumeCleanupRepository cleanupRepository,
            @Value("${job-engine.pdf-generation.cleanup-health-oldest-due-threshold:PT5M}")
            Duration oldestDueDegradedThreshold,
            @Value("${job-engine.pdf-generation.cleanup-health-repeated-failure-attempts:3}")
            int repeatedFailureAttemptThreshold,
            @Value("${job-engine.pdf-generation.cleanup-completed-retention:30d}") Duration completedRetention
    ) {
        this(
                cleanupRepository,
                oldestDueDegradedThreshold,
                repeatedFailureAttemptThreshold,
                completedRetention,
                Clock.systemUTC()
        );
    }

    GeneratedResumeCleanupHealthService(
            GeneratedResumeCleanupRepository cleanupRepository,
            Duration oldestDueDegradedThreshold,
            int repeatedFailureAttemptThreshold,
            Duration completedRetention,
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
        this.completedRetention = requirePositive(completedRetention, "completedRetention");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CleanupHealthReport checkHealth() {
        var now = clock.instant();
        try {
            var snapshot = cleanupRepository.readQueueSnapshot(
                    now,
                    repeatedFailureAttemptThreshold,
                    now.minus(completedRetention)
            );
            var oldestDueAge = snapshot.oldestDueAt() == null
                    ? Duration.ZERO
                    : Duration.between(snapshot.oldestDueAt(), now);
            if (oldestDueAge.isNegative()) {
                oldestDueAge = Duration.ZERO;
            }
            long oldestDueAgeSeconds = oldestDueAge.toSeconds();
            var status = snapshot.repeatedFailure()
                    || snapshot.expiredCompletedCount() > 0
                    || oldestDueAge.compareTo(oldestDueDegradedThreshold) >= 0
                    ? CleanupHealthStatus.DEGRADED
                    : CleanupHealthStatus.HEALTHY;
            return new CleanupHealthReport(
                    status,
                    snapshot.pendingCount(),
                    snapshot.processingCount(),
                    snapshot.expiredCompletedCount(),
                    oldestDueAgeSeconds,
                    snapshot.repeatedFailure()
            );
        } catch (RuntimeException exception) {
            return new CleanupHealthReport(CleanupHealthStatus.UNKNOWN, 0, 0, 0, 0, false);
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
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
            long expiredCompletedCount,
            long oldestDueAgeSeconds,
            boolean repeatedFailure
    ) {
        public CleanupHealthReport {
            Objects.requireNonNull(status, "status must not be null");
            if (pendingCount < 0 || processingCount < 0 || expiredCompletedCount < 0 || oldestDueAgeSeconds < 0) {
                throw new IllegalArgumentException("cleanup health metrics must not be negative");
            }
        }
    }
}
