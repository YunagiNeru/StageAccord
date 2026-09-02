package com.stageaccord.identityaccess.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record SessionState(int authGeneration, AuthStrength strength, Instant authenticatedAt,
        Instant lastSeenAt, Instant absoluteExpiresAt, boolean revoked) {
    private static final Duration IDLE_LIMIT = Duration.ofHours(12);
    private static final Duration FRESH_LIMIT = Duration.ofMinutes(30);

    public SessionState {
        if (authGeneration < 0) throw new IllegalArgumentException("authGeneration must not be negative");
        Objects.requireNonNull(strength, "strength");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        if (absoluteExpiresAt.isAfter(authenticatedAt.plus(Duration.ofDays(7)))) {
            throw new IllegalArgumentException("absolute session lifetime exceeds seven days");
        }
    }

    public void requireValid(int currentGeneration, Instant now, boolean validationAvailable) {
        Objects.requireNonNull(now, "now");
        if (!validationAvailable) {
            throw IdentityRuleViolation.of(IdentityRuleViolation.Reason.SESSION_VALIDATION_UNAVAILABLE);
        }
        if (revoked || authGeneration != currentGeneration || !now.isBefore(absoluteExpiresAt)
                || Duration.between(lastSeenAt, now).compareTo(IDLE_LIMIT) > 0) {
            throw IdentityRuleViolation.of(IdentityRuleViolation.Reason.AUTHENTICATION_REQUIRED);
        }
    }

    public void requireFresh(int currentGeneration, Instant now, boolean validationAvailable) {
        requireValid(currentGeneration, now, validationAvailable);
        if (Duration.between(authenticatedAt, now).compareTo(FRESH_LIMIT) > 0) {
            throw IdentityRuleViolation.of(IdentityRuleViolation.Reason.AUTHENTICATION_REQUIRED);
        }
    }
}
