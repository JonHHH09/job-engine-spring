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
        when(repository.readQueueSnapshot(NOW, 3))
                .thenReturn(new CleanupQueueSnapshot(2, 1, NOW.minusSeconds(299), false));
        var service = service();

        var report = service.checkHealth();

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, report.status());
        assertEquals(2, report.pendingCount());
        assertEquals(1, report.processingCount());
        assertEquals(299, report.oldestDueAgeSeconds());
        assertFalse(report.repeatedFailure());
    }

    @Test
    void reportsDegradedAtOldestDueAgeThreshold() {
        when(repository.readQueueSnapshot(NOW, 3))
                .thenReturn(new CleanupQueueSnapshot(1, 0, NOW.minusSeconds(300), false));

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.DEGRADED, service().checkHealth().status());
    }

    @Test
    void reportsDegradedForRepeatedFailureEvenWithoutDueBacklog() {
        when(repository.readQueueSnapshot(NOW, 3))
                .thenReturn(new CleanupQueueSnapshot(1, 0, null, true));

        var report = service().checkHealth();

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.DEGRADED, report.status());
        assertEquals(0, report.oldestDueAgeSeconds());
    }

    @Test
    void sanitizesRepositoryFailures() {
        when(repository.readQueueSnapshot(NOW, 3))
                .thenThrow(new IllegalStateException("/Users/jh/private/resume.pdf password=secret"));

        var report = service().checkHealth();

        assertEquals(GeneratedResumeCleanupHealthService.CleanupHealthStatus.UNKNOWN, report.status());
        assertFalse(report.toString().contains("/Users/jh"));
        assertFalse(report.toString().contains("secret"));
    }

    @Test
    void rejectsInvalidHealthThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ZERO, 3, Clock.fixed(NOW, ZoneOffset.UTC)
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ofSeconds(-1), 3, Clock.fixed(NOW, ZoneOffset.UTC)
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService(
                repository, Duration.ofMinutes(5), 0, Clock.fixed(NOW, ZoneOffset.UTC)
        ));
    }

    @Test
    void rejectsNegativeCleanupHealthMetrics() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService.CleanupHealthReport(
                GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, -1, 0, 0, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService.CleanupHealthReport(
                GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, 0, -1, 0, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedResumeCleanupHealthService.CleanupHealthReport(
                GeneratedResumeCleanupHealthService.CleanupHealthStatus.HEALTHY, 0, 0, -1, false
        ));
    }

    @Test
    void rejectsNegativeQueueSnapshotCounts() {
        assertThrows(IllegalArgumentException.class, () -> new CleanupQueueSnapshot(-1, 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> new CleanupQueueSnapshot(0, -1, null, false));
    }

    private GeneratedResumeCleanupHealthService service() {
        return new GeneratedResumeCleanupHealthService(
                repository,
                Duration.ofMinutes(5),
                3,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
