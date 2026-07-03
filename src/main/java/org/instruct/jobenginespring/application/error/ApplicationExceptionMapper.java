package org.instruct.jobenginespring.application.error;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

/** Converts arbitrary failures into a sanitized, adapter-neutral application error response. */
public final class ApplicationExceptionMapper {

    public ApplicationErrorResponse toErrorResponse(Throwable throwable) {
        if (throwable instanceof ApplicationException applicationException) {
            return ApplicationErrorResponse.from(applicationException);
        }

        if (throwable instanceof IllegalArgumentException || throwable instanceof NullPointerException) {
            return new ApplicationErrorResponse(
                    ApplicationErrorCode.VALIDATION_ERROR.code(),
                    safeValidationMessage(throwable),
                    Map.of()
            );
        }

        if (throwable instanceof DataIntegrityViolationException) {
            return new ApplicationErrorResponse(
                    ApplicationErrorCode.VALIDATION_ERROR.code(),
                    "Profile data violates a persistence constraint",
                    Map.of()
            );
        }

        return new ApplicationErrorResponse(
                ApplicationErrorCode.INTERNAL_ERROR.code(),
                ApplicationErrorCode.INTERNAL_ERROR.defaultMessage(),
                Map.of()
        );
    }

    private static String safeValidationMessage(Throwable throwable) {
        if (throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return ApplicationErrorCode.VALIDATION_ERROR.defaultMessage();
        }
        return throwable.getMessage();
    }
}
