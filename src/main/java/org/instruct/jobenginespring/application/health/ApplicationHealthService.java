package org.instruct.jobenginespring.application.health;

import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthErrorCategory;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthMetadata;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthStatus;
import org.instruct.jobenginespring.application.health.GeneratedResumeCleanupHealthService.CleanupHealthReport;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Composes sanitized readiness and cleanup backlog health for the MCP surface. */
@Service
public final class ApplicationHealthService {

    private final DatabaseHealthService databaseHealthService;
    private final GeneratedResumeCleanupHealthService cleanupHealthService;

    public ApplicationHealthService(
            DatabaseHealthService databaseHealthService,
            GeneratedResumeCleanupHealthService cleanupHealthService
    ) {
        this.databaseHealthService = Objects.requireNonNull(databaseHealthService, "databaseHealthService must not be null");
        this.cleanupHealthService = Objects.requireNonNull(cleanupHealthService, "cleanupHealthService must not be null");
    }

    public ApplicationHealthReport checkHealth() {
        var database = databaseHealthService.checkHealth();
        return new ApplicationHealthReport(
                database.status(),
                database.errorCategory(),
                database.metadata(),
                cleanupHealthService.checkHealth()
        );
    }

    public record ApplicationHealthReport(
            DatabaseHealthStatus status,
            DatabaseHealthErrorCategory errorCategory,
            DatabaseHealthMetadata metadata,
            CleanupHealthReport generatedResumeCleanup
    ) {
        public ApplicationHealthReport {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(errorCategory, "errorCategory must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(generatedResumeCleanup, "generatedResumeCleanup must not be null");
        }
    }
}
