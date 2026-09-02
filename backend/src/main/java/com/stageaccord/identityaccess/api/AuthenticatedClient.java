package com.stageaccord.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

public record AuthenticatedClient(UUID workspaceId, UUID sessionId, UUID projectId,
        String role, int authGeneration, Instant authenticatedAt, Instant expiresAt) {}
