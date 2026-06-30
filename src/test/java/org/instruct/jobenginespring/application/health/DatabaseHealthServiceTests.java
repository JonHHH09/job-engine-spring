package org.instruct.jobenginespring.application.health;

import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthCheckResult;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthErrorCategory;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthReport;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthStatus;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseHealthServiceTests {

    private static final Instant CHECKED_AT = Instant.parse("2026-06-30T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(CHECKED_AT, ZoneOffset.UTC);

    @Test
    void reportsUpWhenSelectOneSucceeds() {
        DatabaseHealthService service = new DatabaseHealthService(
                DatabaseHealthCheckResult::up,
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.UP, report.status());
        assertEquals(DatabaseHealthErrorCategory.NONE, report.errorCategory());
        assertEquals(CHECKED_AT, report.metadata().checkedAt());
        assertEquals(1, report.metadata().totalChecks());
        assertEquals(1, report.metadata().successfulChecks());
        assertEquals(0, report.metadata().failedChecks());
    }

    @Test
    void reportsDownWithSanitizedPortFailureCategory() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> DatabaseHealthCheckResult.failed(DatabaseHealthErrorCategory.AUTHENTICATION_FAILED),
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.AUTHENTICATION_FAILED, report.errorCategory());
        assertEquals(1, report.metadata().totalChecks());
        assertEquals(0, report.metadata().successfulChecks());
        assertEquals(1, report.metadata().failedChecks());
    }

    @Test
    void sanitizesExceptionDetailsAndMapsSqlStateCategories() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new SQLException(
                            "could not connect to jdbc:postgresql://localhost:5432/job_engine as postgres with password secret",
                            "08001"
                    );
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.CONNECTION_UNAVAILABLE, report.errorCategory());
        assertFalse(report.toString().contains("jdbc:postgresql"));
        assertFalse(report.toString().contains("postgres"));
        assertFalse(report.toString().contains("secret"));
        assertFalse(report.toString().contains("could not connect"));
    }

    @Test
    void mapsSqlAuthenticationStateToAuthenticationFailure() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new SQLException("password authentication failed for user postgres", "28P01");
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.AUTHENTICATION_FAILED, report.errorCategory());
        assertFalse(report.toString().contains("postgres"));
        assertFalse(report.toString().contains("password authentication failed"));
    }

    @Test
    void mapsTimeoutExceptionToTimeoutCategory() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new SQLTimeoutException("statement timed out after 30s");
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.TIMEOUT, report.errorCategory());
    }

    @Test
    void rejectsInconsistentAggregateMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseHealthService.DatabaseHealthMetadata(
                CHECKED_AT,
                2,
                1,
                0
        ));
    }
}
