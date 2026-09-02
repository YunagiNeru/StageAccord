package com.stageaccord.identityaccess.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecoveryApproval(Instant notBefore, UUID requestedBy, UUID approvedBy) {
    public RecoveryApproval {
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(approvedBy, "approvedBy");
        if (requestedBy.equals(approvedBy)) {
            throw IdentityRuleViolation.of(IdentityRuleViolation.Reason.BUSINESS_RULE_VIOLATION);
        }
    }

    public void requireCompletable(Instant now) {
        if (Objects.requireNonNull(now, "now").isBefore(notBefore)) {
            throw IdentityRuleViolation.of(IdentityRuleViolation.Reason.RECOVERY_WAIT_ACTIVE);
        }
    }
}
