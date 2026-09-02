package com.stageaccord.sharedkernel.idempotency;

import java.util.Objects;

public record IdempotencyFingerprint(byte[] scopeHash, byte[] keyHash, byte[] requestHash) {
    public IdempotencyFingerprint {
        scopeHash = requireHash(scopeHash, "scopeHash");
        keyHash = requireHash(keyHash, "keyHash");
        requestHash = requireHash(requestHash, "requestHash");
    }

    @Override public byte[] scopeHash() { return scopeHash.clone(); }
    @Override public byte[] keyHash() { return keyHash.clone(); }
    @Override public byte[] requestHash() { return requestHash.clone(); }

    private static byte[] requireHash(byte[] value, String field) {
        Objects.requireNonNull(value, field);
        if (value.length != 32) throw new IllegalArgumentException(field + " must be 32 bytes");
        return value.clone();
    }
}
