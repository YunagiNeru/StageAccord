package com.stageaccord.sharedkernel.idempotency;

import java.time.Instant;

public interface IdempotencyStore {
    IdempotencyReservation reserve(IdempotencyFingerprint fingerprint, Instant now, Instant expiresAt);

    void complete(IdempotencyFingerprint fingerprint, int statusCode, byte[] responseCiphertext);
}
