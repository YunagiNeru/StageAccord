package com.stageaccord.agreement.domain;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class AgreementAcceptancePolicy {
    public void requireAcceptable(byte[] displayedHash, byte[] currentHash, boolean eligibleParty,
            Instant authenticatedAt, Instant acceptedAt, boolean trustedTime, boolean writeAvailable) {
        if (!writeAvailable) reject(Reason.FEATURE_WRITE_STOPPED);
        if (!trustedTime) reject(Reason.TIME_NOT_TRUSTED);
        if (!eligibleParty) reject(Reason.PARTY_NOT_ELIGIBLE);
        if (displayedHash == null || currentHash == null || !MessageDigest.isEqual(displayedHash, currentHash)) {
            reject(Reason.TARGET_HASH_MISMATCH);
        }
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (Duration.between(authenticatedAt, acceptedAt).isNegative()
                || Duration.between(authenticatedAt, acceptedAt).compareTo(Duration.ofMinutes(30)) > 0) {
            reject(Reason.FRESH_AUTHENTICATION_REQUIRED);
        }
    }

    private static void reject(Reason reason) { throw new AgreementRuleViolation(reason); }
    public enum Reason { FEATURE_WRITE_STOPPED, TIME_NOT_TRUSTED, PARTY_NOT_ELIGIBLE,
        TARGET_HASH_MISMATCH, FRESH_AUTHENTICATION_REQUIRED }
    public static final class AgreementRuleViolation extends RuntimeException {
        private final Reason reason;
        private AgreementRuleViolation(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
