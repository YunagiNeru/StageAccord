package com.stageaccord.identityaccess.domain;

public final class IdentityRuleViolation extends RuntimeException {
    private final Reason reason;

    private IdentityRuleViolation(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public static IdentityRuleViolation of(Reason reason) {
        return new IdentityRuleViolation(reason);
    }

    public Reason reason() { return reason; }

    public enum Reason {
        AUTHENTICATION_REQUIRED,
        BUSINESS_RULE_VIOLATION,
        RECOVERY_WAIT_ACTIVE,
        SESSION_VALIDATION_UNAVAILABLE
    }
}
