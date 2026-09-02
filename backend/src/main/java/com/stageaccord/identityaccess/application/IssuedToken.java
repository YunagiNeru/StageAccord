package com.stageaccord.identityaccess.application;

import java.util.Arrays;

public record IssuedToken(String plaintext, byte[] digest, String digestKeyId) {
    public IssuedToken {
        digest = Arrays.copyOf(digest, digest.length);
    }

    @Override
    public byte[] digest() {
        return Arrays.copyOf(digest, digest.length);
    }
}
