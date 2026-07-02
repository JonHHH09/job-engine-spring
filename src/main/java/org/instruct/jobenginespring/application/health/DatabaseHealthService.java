package org.instruct.jobenginespring.application.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Application use case for checking database readiness without leaking connection details.
 *
 * <p>The outbound port is intentionally minimal for now: implementations should execute
 * a basic {@code SELECT 1} using application credentials and return only a sanitized
 * category. This service aggregates safe status metadata for future inbound adapters.</p>
 */
@Service
public final class DatabaseHealthService {

    private final DatabaseHealthPort databaseHealthPort;
    private final Clock clock;

    @Autowired
    public DatabaseHealthService(DatabaseHealthPort databaseHealthPort) {
        this(databaseHealthPort, Clock.systemUTC());
    }

    DatabaseHealthService(DatabaseHealthPort databaseHealthPort, Clock clock) {
        this.databaseHealthPort = Objects.requireNonNull(databaseHealthPort, "databaseHealthPort must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public DatabaseHealthReport checkHealth() {
        Instant checkedAt = clock.instant();

        try {
            DatabaseHealthCheckResult selectOne = databaseHealthPort.checkSelectOne();
            if (selectOne == null) {
                return down(DatabaseHealthErrorCategory.UNKNOWN, checkedAt);
            }

            if (selectOne.successful()) {
                return new DatabaseHealthReport(
                        DatabaseHealthStatus.UP,
                        DatabaseHealthErrorCategory.NONE,
                        new DatabaseHealthMetadata(checkedAt, 1, 1, 0)
                );
            }

            return down(selectOne.errorCategory(), checkedAt);
        } catch (Exception exception) {
            return down(categorize(exception), checkedAt);
        }
    }

    private DatabaseHealthReport down(DatabaseHealthErrorCategory errorCategory, Instant checkedAt) {
        DatabaseHealthErrorCategory safeCategory = errorCategory == null || errorCategory == DatabaseHealthErrorCategory.NONE
                ? DatabaseHealthErrorCategory.UNKNOWN
                : errorCategory;
        return new DatabaseHealthReport(
                DatabaseHealthStatus.DOWN,
                safeCategory,
                new DatabaseHealthMetadata(checkedAt, 1, 0, 1)
        );
    }

    private static DatabaseHealthErrorCategory categorize(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLInvalidAuthorizationSpecException) {
                return DatabaseHealthErrorCategory.AUTHENTICATION_FAILED;
            }
            if (current instanceof SQLTimeoutException || current instanceof TimeoutException) {
                return DatabaseHealthErrorCategory.TIMEOUT;
            }
            if (current instanceof SQLTransientConnectionException
                    || current instanceof SQLNonTransientConnectionException
                    || current instanceof SQLRecoverableException) {
                return DatabaseHealthErrorCategory.CONNECTION_UNAVAILABLE;
            }
            if (current instanceof SQLException sqlException) {
                DatabaseHealthErrorCategory sqlStateCategory = categorizeSqlState(sqlException.getSQLState());
                if (sqlStateCategory != DatabaseHealthErrorCategory.UNKNOWN) {
                    return sqlStateCategory;
                }
                return DatabaseHealthErrorCategory.QUERY_FAILED;
            }
        }
        return DatabaseHealthErrorCategory.UNKNOWN;
    }

    private static DatabaseHealthErrorCategory categorizeSqlState(String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return DatabaseHealthErrorCategory.UNKNOWN;
        }
        String sqlStateClass = sqlState.substring(0, 2);
        return switch (sqlStateClass) {
            case "08" -> DatabaseHealthErrorCategory.CONNECTION_UNAVAILABLE;
            case "28" -> DatabaseHealthErrorCategory.AUTHENTICATION_FAILED;
            default -> DatabaseHealthErrorCategory.UNKNOWN;
        };
    }

    /**
     * Outbound application port. Implementations should run {@code SELECT 1} and must not
     * return raw exception messages, JDBC URLs, usernames, passwords, or other secrets.
     */
    @FunctionalInterface
    public interface DatabaseHealthPort {
        DatabaseHealthCheckResult checkSelectOne() throws Exception;
    }

    public enum DatabaseHealthStatus {
        UP,
        DOWN
    }

    public enum DatabaseHealthErrorCategory {
        NONE,
        CONNECTION_UNAVAILABLE,
        AUTHENTICATION_FAILED,
        QUERY_FAILED,
        TIMEOUT,
        UNKNOWN
    }

    public record DatabaseHealthCheckResult(boolean successful, DatabaseHealthErrorCategory errorCategory) {
        public DatabaseHealthCheckResult {
            if (successful) {
                errorCategory = DatabaseHealthErrorCategory.NONE;
            } else if (errorCategory == null || errorCategory == DatabaseHealthErrorCategory.NONE) {
                errorCategory = DatabaseHealthErrorCategory.UNKNOWN;
            }
        }

        public static DatabaseHealthCheckResult up() {
            return new DatabaseHealthCheckResult(true, DatabaseHealthErrorCategory.NONE);
        }

        public static DatabaseHealthCheckResult failed(DatabaseHealthErrorCategory errorCategory) {
            return new DatabaseHealthCheckResult(false, errorCategory);
        }
    }

    public record DatabaseHealthReport(
            DatabaseHealthStatus status,
            DatabaseHealthErrorCategory errorCategory,
            DatabaseHealthMetadata metadata
    ) {
        public DatabaseHealthReport {
            status = Objects.requireNonNull(status, "status must not be null");
            errorCategory = Objects.requireNonNull(errorCategory, "errorCategory must not be null");
            metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        }
    }

    public record DatabaseHealthMetadata(
            Instant checkedAt,
            int totalChecks,
            int successfulChecks,
            int failedChecks
    ) {
        public DatabaseHealthMetadata {
            checkedAt = Objects.requireNonNull(checkedAt, "checkedAt must not be null");
            if (totalChecks < 0 || successfulChecks < 0 || failedChecks < 0) {
                throw new IllegalArgumentException("health check counts must not be negative");
            }
            if (successfulChecks + failedChecks != totalChecks) {
                throw new IllegalArgumentException("successful and failed checks must equal total checks");
            }
        }
    }
}
