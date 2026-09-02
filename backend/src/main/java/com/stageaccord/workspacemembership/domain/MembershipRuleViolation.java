package com.stageaccord.workspacemembership.domain;

public final class MembershipRuleViolation extends RuntimeException {
    private final MembershipPolicy.Reason reason;

    private MembershipRuleViolation(MembershipPolicy.Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public static MembershipRuleViolation of(MembershipPolicy.Reason reason) {
        return new MembershipRuleViolation(reason);
    }

    public MembershipPolicy.Reason reason() { return reason; }
}
