package com.stageaccord.auditadmin.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public final class SupportAccessPolicy {
    private static final Duration MAX_GRANT = Duration.ofMinutes(60);

    public void requireUsable(Grant grant, String operation, Instant now,
            boolean vpnConnected, boolean mfaVerified) {
        if (grant == null || now == null || !vpnConnected || !mfaVerified
                || blank(grant.ticketId()) || blank(grant.purpose())
                || grant.requesterId().equals(grant.approverId())
                || grant.expiresAt().isAfter(grant.approvedAt().plus(MAX_GRANT))
                || !now.isBefore(grant.expiresAt()) || grant.revokedAt() != null
                || !grant.allowedOperations().contains(operation)) {
            throw new AdminRuleViolation(Reason.SUPPORT_ACCESS_DENIED);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record Grant(String ticketId, String purpose, String requesterId, String approverId,
            Set<String> allowedOperations, Instant approvedAt, Instant expiresAt, Instant revokedAt) {}
    public enum Reason { SUPPORT_ACCESS_DENIED, RELEASE_CONFIRMATION_REQUIRED }
    public static final class AdminRuleViolation extends RuntimeException {
        private final Reason reason;
        AdminRuleViolation(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
