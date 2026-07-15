package org.instruct.jobenginespring.application.health;

import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthCheckResult;
import org.instruct.jobenginespring.application.health.GeneratedResumeCleanupHealthService.CleanupHealthReport;
import org.instruct.jobenginespring.application.health.GeneratedResumeCleanupHealthService.CleanupHealthStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationHealthServiceTests {

    @Test
    void preservesDatabaseReadinessAndAddsCleanupHealth() {
        var now = Instant.parse("2026-07-14T16:00:00Z");
        var database = new DatabaseHealthService(DatabaseHealthCheckResult::up, Clock.fixed(now, ZoneOffset.UTC));
        var cleanup = mock(GeneratedResumeCleanupHealthService.class);
        var cleanupReport = new CleanupHealthReport(CleanupHealthStatus.DEGRADED, 2, 1, 4, 300, true);
        when(cleanup.checkHealth()).thenReturn(cleanupReport);

        var report = new ApplicationHealthService(database, cleanup).checkHealth();

        assertEquals(DatabaseHealthService.DatabaseHealthStatus.UP, report.status());
        assertEquals(cleanupReport, report.generatedResumeCleanup());
    }
}
