package org.instruct.jobenginespring.application.health;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository.CleanupQueueSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedResumeCleanupHealthServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T16:00:00Z");
    private final GeneratedResumeCleanupRepository repository = mock(GeneratedResumeCleanupRepository.class);

    @Test
    void reportsHealthyBacklogBelowThresholds() {
        when(repository.readQueueSnapshot(NOW, 3, NOW.minus(Duration.ofDays(30))))
                .thenReturn(new CleanupQueueSnapshot(2, 1, 0, NOW.minusSeconds(299), false));
        var service = service();

        var report = service.checkHealth();

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, report.status());
        assertEquals(2, report.pendingCount());
        assertEquals(1, report.processingCount());
        assertEquals(0, report.expiredCompletedCount());
        assertEquals(299, report.oldestDueAgeSeconds());
        assertFalse(report.repeatedFailure());
    }

    @Test
    void reportsDegradedAtOldestDueAgeThreshold() {
        when(repository.readQueueSnapshot(NOW, 3, NOW.minus(Duration.ofDays(30))))
                .thenReturn(new CleanupQueueSnapshot(1, 0, 0, NOW.minusSeconds(300), false));

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.DEGRADED, service().checkHealth().status());
    }

    @Test
    void reportsDegradedForRepeatedFailureEvenWithoutDueBacklog() {
        when(repository.readQueueSnapshot(NOW, 3, NOW.minus(Duration.ofDays(30))))
                .thenReturn(new CleanupQueueSnapshot(1, 0, 0, null, true));

        var report = service().checkHealth();

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.DEGRADED, report.status());
        assertEquals(0, report.oldestDueAgeSeconds());
    }

    @Test
    void reportsDegradedWhenExpiredCompletedRowsRemain() {
        when(repository.readQueueSnapshot(NOW, 3, NOW.minus(Duration.ofDays(30))))
                .thenReturn(new CleanupQueueSnapshot(0, 0, 7, null, false));

        var report = service().checkHealth();

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.DEGRADED, report.status());
        assertEquals(7, report.expiredCompletedCount());
    }

    @Test
    void sanitizesRepositoryFailures() {
        when(repository.readQueueSnapshot(NOW, 3, NOW.minus(Duration.ofDays(30))))
                .thenThrow(new IllegalStateException("/Users/jh/private/resume.pdf password=secret"));

        var report = service().checkHealth();

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.UNKNOWN, report.status());
        assertFalse(report.toString().contains("/Users/jh"));
        assertFalse(report.toString().contains("secret"));
    }

    @Test
    void rejectsInvalidHealthThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ZERO, 3, Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC)
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ofSeconds(-1), 3, Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC)
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ofMinutes(5), 0, Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC)
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ofMinutes(5), 3, Duration.ZERO, Clock.fixed(NOW, ZoneOffset.UTC)
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ofMinutes(5), 3, Duration.ofSeconds(-1), Clock.fixed(NOW, ZoneOffset.UTC)
        ));
        assertThrows(NullPointerException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ofMinutes(5), 3, null, Clock.fixed(NOW, ZoneOffset.UTC)
        ));
    }

    @Test
    void rejectsNegativeCleanupHealthMetrics() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService.CleanupHealthReport(
                GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, -1, 0, 0, 0, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService.CleanupHealthReport(
                GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, 0, -1, 0, 0, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService.CleanupHealthReport(
                GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, 0, 0, -1, 0, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService.CleanupHealthReport(
                GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, 0, 0, 0, -1, false
        ));
    }

    @Test
    void rejectsNegativeQueueSnapshotCounts() {
        assertThrows(IllegalArgumentException.class, () -> new CleanupQueueSnapshot(-1, 0, 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> new CleanupQueueSnapshot(0, -1, 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> new CleanupQueueSnapshot(0, 0, -1, null, false));
    }

    private GeneratedResumeCleanupHealthService service() {
        return new GeneratedResumeCleanupHealthService(
                repository,
                Duration.ofMinutes(5),
                3,
                Duration.ofDays(30),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
