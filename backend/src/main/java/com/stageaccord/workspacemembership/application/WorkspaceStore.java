package com.stageaccord.workspacemembership.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.stageaccord.workspacemembership.domain.WorkspaceRole;

public interface WorkspaceStore {
    void createWorkspace(UUID workspaceId, UUID ownerAccountId, UUID ownerMembershipId,
            String name, Instant now);

    Optional<MembershipSnapshot> findMembership(UUID workspaceId, UUID accountId);

    Optional<MembershipSnapshot> lockMembership(UUID workspaceId, UUID membershipId);

    void createInvitation(InvitationSnapshot invitation);

    Optional<InvitationSnapshot> lockInvitation(UUID workspaceId, UUID invitationId);

    UUID acceptInvitation(InvitationSnapshot invitation, UUID accountId, Instant acceptedAt);

    void revokeInvitation(UUID workspaceId, UUID invitationId, Instant revokedAt);

    void changeRole(UUID workspaceId, UUID membershipId, WorkspaceRole role);

    boolean hasAssignments(UUID workspaceId, UUID membershipId);

    void revokeMembership(UUID workspaceId, UUID membershipId, Instant revokedAt);

    UUID startOwnershipTransfer(UUID workspaceId, UUID fromMembershipId,
            UUID toMembershipId, Instant expiresAt);

    Optional<OwnershipTransferSnapshot> lockOwnershipTransfer(UUID workspaceId, UUID transferId);

    void acceptOwnershipTransfer(OwnershipTransferSnapshot transfer, Instant acceptedAt);
}
