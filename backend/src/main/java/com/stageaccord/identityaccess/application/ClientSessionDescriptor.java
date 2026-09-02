package com.stageaccord.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record ClientSessionDescriptor(UUID workspaceId, UUID id, UUID projectId, String role,
        int authGeneration, Instant authenticatedAt, Instant lastSeenAt,
        Instant absoluteExpiresAt, Instant revokedAt) {}
