package com.stageaccord.intake.domain;

import java.util.Objects;

public final class IntakePolicy {
    public void requireAcceptable(Evaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (!evaluation.rateServiceAvailable()) reject(Reason.RATE_SERVICE_UNAVAILABLE);
        if (!evaluation.writeFeatureAvailable()) reject(Reason.FEATURE_WRITE_STOPPED);
        if (!evaluation.publishedVersion()) reject(Reason.VERSION_NOT_PUBLISHED);
        if (!evaluation.privacyAccepted()) reject(Reason.PRIVACY_NOT_ACCEPTED);
        if (!evaluation.botCheckPassed()) reject(Reason.BOT_REJECTED);
        if (evaluation.senderBlocked()) reject(Reason.SENDER_BLOCKED);
        if (!evaluation.intakeOpen() || !evaluation.capacityAvailable()) reject(Reason.INTAKE_STOPPED);
    }

    private static void reject(Reason reason) { throw new IntakeRuleViolation(reason); }

    public record Evaluation(boolean rateServiceAvailable, boolean writeFeatureAvailable,
            boolean publishedVersion, boolean privacyAccepted, boolean botCheckPassed,
            boolean senderBlocked, boolean intakeOpen, boolean capacityAvailable) {}

    public enum Reason {
        RATE_SERVICE_UNAVAILABLE, FEATURE_WRITE_STOPPED, VERSION_NOT_PUBLISHED,
        PRIVACY_NOT_ACCEPTED, BOT_REJECTED, SENDER_BLOCKED, INTAKE_STOPPED
    }

    public static final class IntakeRuleViolation extends RuntimeException {
        private final Reason reason;
        private IntakeRuleViolation(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
