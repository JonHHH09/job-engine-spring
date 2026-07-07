package org.instruct.jobenginespring.application.error;

/** Stable, protocol-neutral application error codes for adapters to expose safely. */
public enum ApplicationErrorCode {
    VALIDATION_ERROR("validation_error", "Request validation failed"),
    AUTHORIZATION_ERROR("authorization_error", "Request is not authorized"),
    NOT_FOUND("not_found", "Requested resource was not found"),
    INTERNAL_ERROR("internal_error", "Unexpected application error");

    private final String code;
    private final String defaultMessage;

    ApplicationErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
