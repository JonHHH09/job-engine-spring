package org.instruct.jobenginespring.domain.job;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record JobAnalysisRun(
        UUID id,
        String sourceType,
        String originalUrl,
        String normalizedUrl,
        String fetchStatus,
        Integer httpStatus,
        String fetchedTitle,
        String inputSha256,
        Map<String, Object> inputJson,
        String hermesStatus,
        Map<String, Object> hermesResponseJson,
        String hermesResponseSha256,
        String validationStatus,
        List<String> validationErrors,
        UUID createdJobId,
        Instant createdAt,
        Instant updatedAt
) {
    public JobAnalysisRun {
        Objects.requireNonNull(id, "id must not be null");
        requireText(sourceType, "sourceType");
        requireText(fetchStatus, "fetchStatus");
        requireText(inputSha256, "inputSha256");
        Objects.requireNonNull(inputJson, "inputJson must not be null");
        requireText(hermesStatus, "hermesStatus");
        requireText(validationStatus, "validationStatus");
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        inputJson = Map.copyOf(inputJson);
        hermesResponseJson = hermesResponseJson == null ? null : Map.copyOf(hermesResponseJson);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
