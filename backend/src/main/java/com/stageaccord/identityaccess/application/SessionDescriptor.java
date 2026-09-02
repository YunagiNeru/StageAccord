package com.stageaccord.identityaccess.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.stageaccord.identityaccess.domain.AuthStrength;

public record SessionDescriptor(UUID id, UUID accountId, byte[] tokenDigest, String digestKeyId,
        AuthStrength strength, int authGeneration, Instant authenticatedAt, Instant lastSeenAt,
        Instant absoluteExpiresAt, Instant revokedAt) {
    public SessionDescriptor {
        tokenDigest = Arrays.copyOf(tokenDigest, tokenDigest.length);
    }

    @Override public byte[] tokenDigest() { return Arrays.copyOf(tokenDigest, tokenDigest.length); }
}
