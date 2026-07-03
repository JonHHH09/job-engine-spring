package org.instruct.jobenginespring.application.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationExceptionMapperTests {

    private final ApplicationExceptionMapper mapper = new ApplicationExceptionMapper();

    @Test
    void mapsApplicationExceptionToSafeErrorResponse() {
        RuntimeException cause = new RuntimeException("database password should not be exposed");
        ApplicationException exception = new ApplicationException(
                ApplicationErrorCode.NOT_FOUND,
                "Profile not found: 123",
                Map.of("resource", "profile"),
                cause
        );

        ApplicationErrorResponse response = mapper.toErrorResponse(exception);

        assertEquals("not_found", response.code());
        assertEquals("Profile not found: 123", response.message());
        assertEquals(Map.of("resource", "profile"), response.details());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void supportsSimpleApplicationExceptionConstructor() {
        ApplicationException exception = new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Request field is invalid"
        );

        assertEquals(ApplicationErrorCode.VALIDATION_ERROR, exception.errorCode());
        assertEquals("Request field is invalid", exception.safeMessage());
        assertEquals(Map.of(), exception.details());
    }

    @Test
    void usesDefaultApplicationMessageWhenSafeMessageIsBlank() {
        ApplicationException exception = new ApplicationException(
                ApplicationErrorCode.NOT_FOUND,
                " ",
                null,
                null
        );

        ApplicationErrorResponse response = ApplicationErrorResponse.from(exception);

        assertEquals("not_found", response.code());
        assertEquals("Requested resource was not found", response.message());
        assertEquals(Map.of(), response.details());
    }

    @Test
    void usesDefaultApplicationMessageWhenSafeMessageIsNull() {
        ApplicationException exception = new ApplicationException(
                ApplicationErrorCode.INTERNAL_ERROR,
                null,
                Map.of(),
                null
        );

        assertEquals("Unexpected application error", exception.safeMessage());
    }

    @Test
    void mapsValidationExceptionsWithoutStackTraceOrCauseDetails() {
        ApplicationErrorResponse illegalArgument = mapper.toErrorResponse(new IllegalArgumentException("fullName must not be blank"));
        ApplicationErrorResponse nullPointer = mapper.toErrorResponse(new NullPointerException());
        ApplicationErrorResponse blankArgument = mapper.toErrorResponse(new IllegalArgumentException(" "));

        assertEquals("validation_error", illegalArgument.code());
        assertEquals("fullName must not be blank", illegalArgument.message());
        assertEquals(Map.of(), illegalArgument.details());
        assertEquals("validation_error", nullPointer.code());
        assertEquals("Request validation failed", nullPointer.message());
        assertEquals(Map.of(), nullPointer.details());
        assertEquals("validation_error", blankArgument.code());
        assertEquals("Request validation failed", blankArgument.message());
    }

    @Test
    void mapsUnexpectedExceptionToGenericInternalError() {
        ApplicationErrorResponse response = mapper.toErrorResponse(new RuntimeException("sensitive details"));

        assertEquals("internal_error", response.code());
        assertEquals("Unexpected application error", response.message());
        assertEquals(Map.of(), response.details());
    }

    @Test
    void mapsDataIntegrityExceptionsToSanitizedValidationError() {
        ApplicationErrorResponse response = mapper.toErrorResponse(new DataIntegrityViolationException("sensitive constraint detail"));

        assertEquals("validation_error", response.code());
        assertEquals("Profile data violates a persistence constraint", response.message());
        assertEquals(Map.of(), response.details());
    }

    @Test
    void errorResponseDefensivelyCopiesDetails() {
        Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("resource", "profile");

        ApplicationErrorResponse response = new ApplicationErrorResponse("not_found", "Missing", details);
        ApplicationErrorResponse nullDetailsResponse = new ApplicationErrorResponse("internal_error", "Unexpected", null);
        details.put("profileId", "changed");

        assertEquals(Map.of("resource", "profile"), response.details());
        assertEquals(Map.of(), nullDetailsResponse.details());
        assertThrows(UnsupportedOperationException.class, () -> response.details().put("new", "value"));
    }

    @Test
    void rejectsInvalidErrorInputs() {
        assertThrows(NullPointerException.class, () -> new ApplicationException(null, "message"));
        assertThrows(NullPointerException.class, () -> new ApplicationErrorResponse(null, "message", Map.of()));
        assertThrows(NullPointerException.class, () -> new ApplicationErrorResponse("code", null, Map.of()));
        assertThrows(NullPointerException.class, () -> ApplicationErrorResponse.from(null));
    }
}
