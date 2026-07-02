package org.instruct.jobenginespring.application.health;

import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthCheckResult;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthErrorCategory;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthReport;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeoutException;

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
    void publicConstructorUsesSystemClock() {
        DatabaseHealthService service = new DatabaseHealthService(DatabaseHealthCheckResult::up);

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.UP, report.status());
    }

    @Test
    void rejectsNullConstructorInputs() {
        assertThrows(NullPointerException.class, () -> new DatabaseHealthService(null));
        assertThrows(NullPointerException.class, () -> new DatabaseHealthService(DatabaseHealthCheckResult::up, null));
    }

    @Test
    void reportsUnknownWhenPortReturnsNull() {
        DatabaseHealthService service = new DatabaseHealthService(() -> null, CLOCK);

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.UNKNOWN, report.errorCategory());
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
    void normalizesUnsafeFailedCheckCategoriesToUnknown() {
        assertEquals(DatabaseHealthErrorCategory.UNKNOWN, DatabaseHealthCheckResult.failed(null).errorCategory());
        assertEquals(DatabaseHealthErrorCategory.UNKNOWN,
                DatabaseHealthCheckResult.failed(DatabaseHealthErrorCategory.NONE).errorCategory());

        DatabaseHealthService service = new DatabaseHealthService(
                () -> DatabaseHealthCheckResult.failed(DatabaseHealthErrorCategory.NONE),
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.UNKNOWN, report.errorCategory());
    }

    @Test
    void downReportNormalizesNullAndNoneCategoriesToUnknown() throws Exception {
        assertEquals(DatabaseHealthErrorCategory.UNKNOWN, invokeDown(null).errorCategory());
        assertEquals(DatabaseHealthErrorCategory.UNKNOWN, invokeDown(DatabaseHealthErrorCategory.NONE).errorCategory());
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
    void mapsSqlAuthorizationExceptionToAuthenticationFailure() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new SQLInvalidAuthorizationSpecException("bad credentials");
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.AUTHENTICATION_FAILED, report.errorCategory());
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
    void mapsNestedTimeoutExceptionToTimeoutCategory() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new RuntimeException(new TimeoutException("connect timed out"));
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.TIMEOUT, report.errorCategory());
    }

    @Test
    void mapsConnectionExceptionsToConnectionUnavailable() {
        assertConnectionUnavailable(new SQLTransientConnectionException("transient"));
        assertConnectionUnavailable(new SQLNonTransientConnectionException("non transient"));
        assertConnectionUnavailable(new SQLRecoverableException("recoverable"));
    }

    @Test
    void mapsGenericSqlFailuresToQueryFailed() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new SQLException("syntax error", "42000");
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.QUERY_FAILED, report.errorCategory());
    }

    @Test
    void mapsSqlFailuresWithoutStateToQueryFailed() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new SQLException("syntax error", (String) null);
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.QUERY_FAILED, report.errorCategory());
    }

    @Test
    void mapsShortSqlStateToQueryFailed() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new SQLException("syntax error", "0");
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.QUERY_FAILED, report.errorCategory());
    }

    @Test
    void mapsGenericExceptionsToUnknown() {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw new IllegalStateException("unexpected");
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.UNKNOWN, report.errorCategory());
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

    @Test
    void rejectsNegativeHealthMetadataCounts() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseHealthService.DatabaseHealthMetadata(
                CHECKED_AT,
                -1,
                0,
                0
        ));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseHealthService.DatabaseHealthMetadata(
                CHECKED_AT,
                1,
                -1,
                2
        ));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseHealthService.DatabaseHealthMetadata(
                CHECKED_AT,
                1,
                2,
                -1
        ));
    }

    @Test
    void healthRecordsRejectMissingRequiredValues() {
        assertThrows(NullPointerException.class, () -> new DatabaseHealthService.DatabaseHealthReport(
                null,
                DatabaseHealthErrorCategory.NONE,
                new DatabaseHealthService.DatabaseHealthMetadata(CHECKED_AT, 1, 1, 0)
        ));
        assertThrows(NullPointerException.class, () -> new DatabaseHealthService.DatabaseHealthReport(
                DatabaseHealthStatus.UP,
                null,
                new DatabaseHealthService.DatabaseHealthMetadata(CHECKED_AT, 1, 1, 0)
        ));
        assertThrows(NullPointerException.class, () -> new DatabaseHealthService.DatabaseHealthReport(
                DatabaseHealthStatus.UP,
                DatabaseHealthErrorCategory.NONE,
                null
        ));
        assertThrows(NullPointerException.class, () -> new DatabaseHealthService.DatabaseHealthMetadata(
                null,
                1,
                1,
                0
        ));
    }

    private static void assertConnectionUnavailable(Exception exception) {
        DatabaseHealthService service = new DatabaseHealthService(
                () -> {
                    throw exception;
                },
                CLOCK
        );

        DatabaseHealthReport report = service.checkHealth();

        assertEquals(DatabaseHealthStatus.DOWN, report.status());
        assertEquals(DatabaseHealthErrorCategory.CONNECTION_UNAVAILABLE, report.errorCategory());
    }

    private static DatabaseHealthReport invokeDown(DatabaseHealthErrorCategory category) throws Exception {
        DatabaseHealthService service = new DatabaseHealthService(DatabaseHealthCheckResult::up, CLOCK);
        Method down = DatabaseHealthService.class.getDeclaredMethod(
                "down",
                DatabaseHealthErrorCategory.class,
                Instant.class
        );
        down.setAccessible(true);
        return (DatabaseHealthReport) down.invoke(service, category, CHECKED_AT);
    }
}
