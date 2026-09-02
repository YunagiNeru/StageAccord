package com.stageaccord.identityaccess.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public record AuthChallenge(UUID id, UUID accountId, String purpose, byte[] tokenDigest,
        String digestKeyId, byte[] subjectDigest, ProtectedValue protectedSubject,
        Instant expiresAt, Instant consumedAt) {
    public AuthChallenge {
        tokenDigest = Arrays.copyOf(tokenDigest, tokenDigest.length);
        subjectDigest = subjectDigest == null ? null : Arrays.copyOf(subjectDigest, subjectDigest.length);
    }

    @Override public byte[] tokenDigest() { return Arrays.copyOf(tokenDigest, tokenDigest.length); }
    @Override public byte[] subjectDigest() {
        return subjectDigest == null ? null : Arrays.copyOf(subjectDigest, subjectDigest.length);
    }
}
