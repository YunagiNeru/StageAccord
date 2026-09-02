package com.stageaccord.sharedkernel.web;

import java.net.URI;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.stageaccord.sharedkernel.application.CommandRejectedException;
import com.stageaccord.sharedkernel.application.RejectionCode;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler(ApiFailure.class)
    public ProblemDetail handleApiFailure(ApiFailure failure) {
        return problem(failure.status(), failure.code());
    }

    @ExceptionHandler(CommandRejectedException.class)
    ProblemDetail handleCommandRejection(CommandRejectedException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REQUIRED -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_IN_PROGRESS, VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case DATABASE_UNAVAILABLE, SESSION_VALIDATION_UNAVAILABLE, AUDIT_UNAVAILABLE,
                    FEATURE_WRITE_STOPPED, OBJECT_STORAGE_UNAVAILABLE, TIME_TRUST_UNAVAILABLE ->
                HttpStatus.SERVICE_UNAVAILABLE;
        };
        return problem(status, failure.code().name());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, ConstraintViolationException.class})
    ProblemDetail handleInvalidInput(Exception ignored) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConstraintConflict(DataIntegrityViolationException ignored) {
        return problem(HttpStatus.CONFLICT, "CONSTRAINT_CONFLICT");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ignored) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    private static ProblemDetail problem(HttpStatus status, String code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(status.getReasonPhrase());
        detail.setType(URI.create("https://problems.stageaccord.local/" + code.toLowerCase().replace('_', '-')));
        detail.setProperty("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) detail.setProperty("correlationId", correlationId);
        return detail;
    }
}
