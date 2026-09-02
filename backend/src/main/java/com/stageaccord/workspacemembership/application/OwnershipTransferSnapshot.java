package com.stageaccord.workspacemembership.application;

import java.time.Instant;
import java.util.UUID;

public record OwnershipTransferSnapshot(UUID workspaceId, UUID transferId, UUID fromMembershipId,
        UUID toMembershipId, String status, Instant expiresAt) {}
