package com.stageaccord.workspacemembership.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.stageaccord.sharedkernel.web.ApiExceptionHandler;
import com.stageaccord.sharedkernel.web.ApiFailure;
import com.stageaccord.workspacemembership.application.WorkspaceApplicationException;

@RestControllerAdvice
@Profile("app")
public final class WorkspaceApiExceptionHandler {
    @ExceptionHandler(WorkspaceApplicationException.class)
    ProblemDetail handleWorkspaceFailure(WorkspaceApplicationException failure) {
        HttpStatus status = switch (failure.code()) {
            case INVALID_REQUEST, INVALID_CHALLENGE -> HttpStatus.BAD_REQUEST;
            case AUTHORIZATION_DENIED, AUTH_FRESHNESS_REQUIRED -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case WORKSPACE_ALREADY_EXISTS, CHALLENGE_CONSUMED, SECRET_ALREADY_ISSUED,
                    ACTIVE_ASSIGNMENT_REMAINS, OWNERSHIP_TRANSFER_REQUIRED -> HttpStatus.CONFLICT;
        };
        return new ApiExceptionHandler().handleApiFailure(ApiFailure.of(status, failure.code().name()));
    }
}
