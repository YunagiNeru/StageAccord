package com.stageaccord.identityaccess.application;

public final class IdentityApplicationException extends RuntimeException {
    public enum Code {
        INVALID_CHALLENGE,
        CHALLENGE_CONSUMED,
        CREDENTIAL_ALREADY_REGISTERED,
        AUTHENTICATION_REQUIRED,
        BUSINESS_RULE_VIOLATION,
        SESSION_VALIDATION_UNAVAILABLE
    }

    private final Code code;

    private IdentityApplicationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public static IdentityApplicationException of(Code code) {
        return new IdentityApplicationException(code);
    }

    public Code code() { return code; }
}
