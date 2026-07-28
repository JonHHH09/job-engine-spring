package org.instruct.jobenginespring.application.error;

/** Stable, protocol-neutral application error codes for adapters to expose safely. */
public enum ApplicationErrorCode {
    VALIDATION_ERROR("validation_error", "Request validation failed"),
    AUTHORIZATION_ERROR("authorization_error", "Request is not authorized"),
    NOT_FOUND("not_found", "Requested resource was not found"),
    CONFLICT("conflict", "Resource revision conflict"),
    UPSTREAM_RATE_LIMITED("upstream_rate_limited", "Upstream service rate limit exceeded"),
    UPSTREAM_INVALID_RESPONSE("upstream_invalid_response", "Upstream service returned an invalid response"),
    UPSTREAM_UNAVAILABLE("upstream_unavailable", "Upstream service is temporarily unavailable"),
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
