package com.stageaccord.identityaccess.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.stageaccord.identityaccess.application.IdentityApplicationException;
import com.stageaccord.sharedkernel.web.ApiFailure;

@RestControllerAdvice
@Profile("app")
public final class IdentityApiExceptionHandler {
    @ExceptionHandler(IdentityApplicationException.class)
    ProblemDetail handleIdentityFailure(IdentityApplicationException failure) {
        HttpStatus status = switch (failure.code()) {
            case INVALID_CHALLENGE -> HttpStatus.BAD_REQUEST;
            case CHALLENGE_CONSUMED, CREDENTIAL_ALREADY_REGISTERED -> HttpStatus.CONFLICT;
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case BUSINESS_RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SESSION_VALIDATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return new com.stageaccord.sharedkernel.web.ApiExceptionHandler()
                .handleApiFailure(ApiFailure.of(status, failure.code().name()));
    }

    @ExceptionHandler(MailException.class)
    ProblemDetail handleMailFailure(MailException ignored) {
        return new com.stageaccord.sharedkernel.web.ApiExceptionHandler()
                .handleApiFailure(ApiFailure.of(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE"));
    }
}
