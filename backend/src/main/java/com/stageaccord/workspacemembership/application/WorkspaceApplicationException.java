package com.stageaccord.workspacemembership.application;

public final class WorkspaceApplicationException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST,
        AUTHORIZATION_DENIED,
        AUTH_FRESHNESS_REQUIRED,
        RESOURCE_NOT_FOUND,
        WORKSPACE_ALREADY_EXISTS,
        INVALID_CHALLENGE,
        CHALLENGE_CONSUMED,
        SECRET_ALREADY_ISSUED,
        ACTIVE_ASSIGNMENT_REMAINS,
        OWNERSHIP_TRANSFER_REQUIRED
    }

    private final Code code;

    private WorkspaceApplicationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public static WorkspaceApplicationException of(Code code) {
        return new WorkspaceApplicationException(code);
    }

    public Code code() { return code; }
}
