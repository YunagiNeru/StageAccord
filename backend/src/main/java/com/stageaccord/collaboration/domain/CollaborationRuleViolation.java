package com.stageaccord.collaboration.domain;

public final class CollaborationRuleViolation extends RuntimeException {
    private final Reason reason;

    CollaborationRuleViolation(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public enum Reason {
        REVISION_WINDOW_CLOSED,
        REVISION_QUOTA_EXHAUSTED,
        CHANGE_ORDER_NOT_ACCEPTED,
        APPROVER_NOT_ELIGIBLE,
        EXPLICIT_ACTION_REQUIRED,
        AUTHENTICATION_STALE,
        TIME_TRUST_UNAVAILABLE,
        TARGET_VERSION_MISMATCH,
        APPROVAL_INCOMPLETE,
        DELIVERY_ITEM_NOT_READY,
        DELIVERY_MANIFEST_MISMATCH,
        PACKAGE_NOT_DELIVERED
    }
}
