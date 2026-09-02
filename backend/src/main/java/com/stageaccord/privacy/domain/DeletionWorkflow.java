package com.stageaccord.privacy.domain;

import java.time.Duration;
import java.time.Instant;

public final class DeletionWorkflow {
    private final Instant requestedAt;
    private Status status = Status.CREATED;

    public DeletionWorkflow(Instant requestedAt) {
        if (requestedAt == null) throw new IllegalArgumentException("requestedAt");
        this.requestedAt = requestedAt;
    }

    public Deadlines deadlines() {
        return new Deadlines(requestedAt.plus(Duration.ofHours(24)), requestedAt.plus(Duration.ofDays(30)),
                requestedAt.plus(Duration.ofDays(35)));
    }

    public void markLedgerPending() { require(Status.CREATED); status = Status.LEDGER_PENDING; }
    public void acknowledgeIndependentLedger(boolean signatureVerified) {
        require(Status.LEDGER_PENDING);
        if (!signatureVerified) fail(Reason.LEDGER_NOT_VERIFIED);
        status = Status.LEDGER_ACKED;
    }
    public void beginProcessing(boolean legalHoldActive) {
        require(Status.LEDGER_ACKED);
        status = legalHoldActive ? Status.HELD : Status.PROCESSING;
    }
    public void resumeAfterHold() { require(Status.HELD); status = Status.PROCESSING; }
    public void complete(boolean everyTargetDeleted) {
        require(Status.PROCESSING);
        if (!everyTargetDeleted) fail(Reason.TARGETS_INCOMPLETE);
        status = Status.COMPLETED;
    }

    public Status status() { return status; }

    private void require(Status expected) {
        if (status != expected) fail(Reason.INVALID_TRANSITION);
    }
    private static void fail(Reason reason) { throw new PrivacyRuleViolation(reason); }

    public enum Status { CREATED, LEDGER_PENDING, LEDGER_ACKED, PROCESSING, HELD, COMPLETED }
    public enum Reason { INVALID_TRANSITION, LEDGER_NOT_VERIFIED, TARGETS_INCOMPLETE }
    public record Deadlines(Instant cacheAndSearch, Instant primaryAndObjects, Instant backups) {}
    public static final class PrivacyRuleViolation extends RuntimeException {
        private final Reason reason;
        private PrivacyRuleViolation(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
