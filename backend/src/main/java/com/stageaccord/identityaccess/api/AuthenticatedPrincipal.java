package com.stageaccord.identityaccess.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public record AuthenticatedPrincipal(UUID accountId, byte[] emailDigest, String strength,
        Instant authenticatedAt) {
    private static final Duration FRESH_LIMIT = Duration.ofMinutes(30);

    public AuthenticatedPrincipal {
        emailDigest = Arrays.copyOf(emailDigest, emailDigest.length);
    }

    @Override public byte[] emailDigest() { return Arrays.copyOf(emailDigest, emailDigest.length); }

    public boolean isFresh(Instant now) {
        return !now.isBefore(authenticatedAt) && !now.isAfter(authenticatedAt.plus(FRESH_LIMIT));
    }
}
