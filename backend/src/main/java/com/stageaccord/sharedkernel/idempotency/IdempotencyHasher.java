package com.stageaccord.sharedkernel.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class IdempotencyHasher {
    private final SecretKey key;

    public IdempotencyHasher(SecretKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    public IdempotencyFingerprint fingerprint(UUID actorId, UUID workspaceId, String operationId,
            String opaqueKey, byte[] canonicalRequest) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        requireText(operationId, "operationId");
        requireText(opaqueKey, "opaqueKey");
        Objects.requireNonNull(canonicalRequest, "canonicalRequest");

        return new IdempotencyFingerprint(
                hmac(lengthPrefixed(actorId.toString(), workspaceId.toString(), operationId)),
                hmac(lengthPrefixed(opaqueKey)),
                sha256(canonicalRequest));
    }

    private byte[] hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(value);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", error);
        }
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private byte[] lengthPrefixed(String... values) {
        int size = 0;
        byte[][] encoded = new byte[values.length][];
        for (int index = 0; index < values.length; index++) {
            encoded[index] = values[index].getBytes(StandardCharsets.UTF_8);
            size += Integer.BYTES + encoded[index].length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] value : encoded) buffer.putInt(value.length).put(value);
        return buffer.array();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
