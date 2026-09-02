package com.stageaccord.identityaccess.api;

import java.util.Arrays;

public record IssuedOpaqueToken(String plaintext, byte[] digest, String digestKeyId) {
    public IssuedOpaqueToken {
        digest = Arrays.copyOf(digest, digest.length);
    }

    @Override public byte[] digest() { return Arrays.copyOf(digest, digest.length); }
}
