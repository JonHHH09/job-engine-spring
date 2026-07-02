package org.instruct.jobenginespring.application.error;

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
                    java.util.Map.of()
            );
        }

        return new ApplicationErrorResponse(
                ApplicationErrorCode.INTERNAL_ERROR.code(),
                ApplicationErrorCode.INTERNAL_ERROR.defaultMessage(),
                java.util.Map.of()
        );
    }

    private static String safeValidationMessage(Throwable throwable) {
        if (throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return ApplicationErrorCode.VALIDATION_ERROR.defaultMessage();
        }
        return throwable.getMessage();
    }
}
