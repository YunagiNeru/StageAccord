package com.stageaccord.workspacemembership.application;

import java.util.UUID;

import com.stageaccord.workspacemembership.domain.WorkspaceRole;

public record MembershipSnapshot(UUID workspaceId, UUID membershipId, UUID accountId,
        WorkspaceRole role, String status) {}
