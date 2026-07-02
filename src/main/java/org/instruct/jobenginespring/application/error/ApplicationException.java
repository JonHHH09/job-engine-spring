package org.instruct.jobenginespring.application.error;

import java.util.Map;
import java.util.Objects;

/** Base exception for application failures that are safe to surface through adapters. */
public class ApplicationException extends RuntimeException {

    private final ApplicationErrorCode errorCode;
    private final Map<String, String> details;

    public ApplicationException(ApplicationErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, Map.of(), null);
    }

    public ApplicationException(
            ApplicationErrorCode errorCode,
            String safeMessage,
            Map<String, String> details,
            Throwable cause
    ) {
        super(resolveMessage(errorCode, safeMessage), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApplicationErrorCode errorCode() {
        return errorCode;
    }

    public String safeMessage() {
        return getMessage();
    }

    public Map<String, String> details() {
        return details;
    }

    private static String resolveMessage(ApplicationErrorCode errorCode, String safeMessage) {
        ApplicationErrorCode safeErrorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        return safeMessage == null || safeMessage.isBlank() ? safeErrorCode.defaultMessage() : safeMessage;
    }
}
