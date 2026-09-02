package com.stageaccord.workspacemembership.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.stageaccord.workspacemembership.domain.WorkspaceRole;

public record InvitationSnapshot(UUID workspaceId, UUID invitationId, byte[] tokenDigest,
        String digestKeyId, byte[] emailDigest, WorkspaceRole role, Instant expiresAt,
        Instant consumedAt, Instant revokedAt) {
    public InvitationSnapshot {
        tokenDigest = Arrays.copyOf(tokenDigest, tokenDigest.length);
        emailDigest = Arrays.copyOf(emailDigest, emailDigest.length);
    }

    @Override public byte[] tokenDigest() { return Arrays.copyOf(tokenDigest, tokenDigest.length); }
    @Override public byte[] emailDigest() { return Arrays.copyOf(emailDigest, emailDigest.length); }
}
