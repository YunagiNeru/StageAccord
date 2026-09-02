package com.stageaccord.billing.domain;

import java.time.Duration;
import java.time.Instant;

public final class BillingPolicy {
    private static final Duration PAYMENT_GRACE = Duration.ofDays(7);

    public EntitlementState resolveWebhook(CurrentEntitlement current, VerifiedEvent event) {
        if (event == null || !event.signatureVerified() || !event.apiVerified()) {
            return current.state();
        }
        if (!event.providerUpdatedAt().isAfter(current.providerUpdatedAt())) return current.state();
        if (expands(current.state(), event.requestedState()) && !event.reconciliationAvailable()) {
            return current.state();
        }
        return event.requestedState();
    }

    public EntitlementState stateAfterPaymentFailure(Instant failedAt, Instant now) {
        if (failedAt == null || now == null) return EntitlementState.RESTRICTED;
        return now.isBefore(failedAt.plus(PAYMENT_GRACE)) ? EntitlementState.GRACE : EntitlementState.RESTRICTED;
    }

    public UsageDecision evaluateUsage(long current, long requestedIncrease, long limit) {
        if (current < 0 || requestedIncrease < 0 || limit < 1) return new UsageDecision(true, true);
        boolean warn = current * 100 >= limit * 80;
        boolean blockNew = current >= limit || requestedIncrease > limit - current;
        return new UsageDecision(warn, blockNew);
    }

    public Capabilities capabilities(EntitlementState state) {
        boolean write = state == EntitlementState.TRIAL || state == EntitlementState.ACTIVE
                || state == EntitlementState.GRACE;
        return new Capabilities(true, true, true, write);
    }

    private static boolean expands(EntitlementState current, EntitlementState requested) {
        return rank(requested) > rank(current);
    }

    private static int rank(EntitlementState state) {
        return switch (state) {
            case CANCELLED -> 0;
            case RESTRICTED -> 1;
            case GRACE -> 2;
            case TRIAL, ACTIVE -> 3;
        };
    }

    public enum EntitlementState { TRIAL, ACTIVE, GRACE, RESTRICTED, CANCELLED }
    public record CurrentEntitlement(EntitlementState state, Instant providerUpdatedAt) {}
    public record VerifiedEvent(boolean signatureVerified, boolean apiVerified, boolean reconciliationAvailable,
            Instant providerUpdatedAt, EntitlementState requestedState) {}
    public record UsageDecision(boolean warnAtEightyPercent, boolean blockNewWrites) {}
    public record Capabilities(boolean canRead, boolean canExport, boolean canUpdatePayment, boolean canCreate) {}
}
