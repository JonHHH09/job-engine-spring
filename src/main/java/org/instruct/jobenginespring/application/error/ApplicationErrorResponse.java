package org.instruct.jobenginespring.application.error;

import java.util.Map;
import java.util.Objects;

/** JSON-serializable, sanitized error shape shared by inbound adapters. */
public record ApplicationErrorResponse(
        String code,
        String message,
        Map<String, String> details
) {
    public ApplicationErrorResponse {
        code = Objects.requireNonNull(code, "code must not be null");
        message = Objects.requireNonNull(message, "message must not be null");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApplicationErrorResponse from(ApplicationException exception) {
        Objects.requireNonNull(exception, "exception must not be null");
        return new ApplicationErrorResponse(
                exception.errorCode().code(),
                exception.safeMessage(),
                exception.details()
        );
    }
}
