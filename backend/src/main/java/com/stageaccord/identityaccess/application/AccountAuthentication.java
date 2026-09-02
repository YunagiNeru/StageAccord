package com.stageaccord.identityaccess.application;

import java.util.Arrays;
import java.util.UUID;

public record AccountAuthentication(UUID accountId, byte[] emailDigest, String status, int authGeneration,
        String encodedPassword, ProtectedValue protectedTotpSecret) {
    public AccountAuthentication {
        emailDigest = Arrays.copyOf(emailDigest, emailDigest.length);
    }

    @Override public byte[] emailDigest() { return Arrays.copyOf(emailDigest, emailDigest.length); }
}
