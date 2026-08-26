package org.instruct.jobenginespring.adapter.in.http.operator;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(basePackageClasses = OperatorFoundationController.class)
final class OperatorProblemHandler {

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> applicationException(ApplicationException exception) {
        HttpStatus status = switch (exception.errorCode()) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case AUTHORIZATION_ERROR -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UPSTREAM_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_INVALID_RESPONSE, UPSTREAM_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(problem(status, exception.errorCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformedRequest() {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, ApplicationErrorCode.VALIDATION_ERROR));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpectedFailure() {
        return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR, ApplicationErrorCode.INTERNAL_ERROR));
    }

    private ProblemDetail problem(HttpStatus status, ApplicationErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, code.defaultMessage());
        problem.setType(URI.create("urn:job-engine:problem:" + code.code()));
        problem.setTitle(code.defaultMessage());
        return problem;
    }
}
