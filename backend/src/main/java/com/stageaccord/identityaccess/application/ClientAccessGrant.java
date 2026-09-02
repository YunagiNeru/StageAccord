package com.stageaccord.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record ClientAccessGrant(UUID workspaceId, UUID id, UUID projectId, byte[] emailDigest,
        String role, int authGeneration, Instant expiresAt, Instant consumedAt, Instant revokedAt) {
    public ClientAccessGrant { emailDigest = emailDigest.clone(); }
    @Override public byte[] emailDigest() { return emailDigest.clone(); }
}
