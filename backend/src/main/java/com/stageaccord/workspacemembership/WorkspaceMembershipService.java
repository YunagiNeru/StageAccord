package com.stageaccord.workspacemembership;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.workspacemembership.application.InvitationMessageSender;
import com.stageaccord.workspacemembership.application.InvitationSnapshot;
import com.stageaccord.workspacemembership.application.MembershipSnapshot;
import com.stageaccord.workspacemembership.application.OwnershipTransferSnapshot;
import com.stageaccord.workspacemembership.application.WorkspaceApplicationException;
import com.stageaccord.workspacemembership.application.WorkspaceStore;
import com.stageaccord.workspacemembership.domain.MembershipPolicy;
import com.stageaccord.workspacemembership.domain.MembershipRuleViolation;
import com.stageaccord.workspacemembership.domain.WorkspaceRole;

@Service
public class WorkspaceMembershipService {
    private static final Duration INVITATION_LIFETIME = Duration.ofDays(7);
    private static final Duration OWNERSHIP_TRANSFER_LIFETIME = Duration.ofHours(24);

    private final WorkspaceStore store;
    private final IdentityAccessGateway identities;
    private final InvitationMessageSender messages;
    private final MembershipPolicy policy;
    private final Clock clock;

    public WorkspaceMembershipService(WorkspaceStore store, IdentityAccessGateway identities,
            InvitationMessageSender messages) {
        this(store, identities, messages, new MembershipPolicy(), Clock.systemUTC());
    }

    public WorkspaceMembershipService(WorkspaceStore store, IdentityAccessGateway identities,
            InvitationMessageSender messages, MembershipPolicy policy, Clock clock) {
        this.store = store;
        this.identities = identities;
        this.messages = messages;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public WorkspaceCreated createWorkspace(String sessionToken, String name) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        UUID workspaceId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        try {
            store.createWorkspace(workspaceId, principal.accountId(), membershipId, name.strip(), clock.instant());
        } catch (DataIntegrityViolationException duplicate) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.WORKSPACE_ALREADY_EXISTS);
        }
        return new WorkspaceCreated(workspaceId, membershipId);
    }

    public void inviteMember(String sessionToken, UUID workspaceId, String email, WorkspaceRole role) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        MembershipSnapshot actor = findActiveByAccount(workspaceId, principal.accountId());
        requireInvitationRole(actor.role(), role);
        var token = identities.issueToken();
        UUID invitationId = UUID.randomUUID();
        try {
            store.createInvitation(new InvitationSnapshot(workspaceId, invitationId, token.digest(),
                    token.digestKeyId(), identities.emailDigest(email), role,
                    clock.instant().plus(INVITATION_LIFETIME), null, null));
        } catch (DataIntegrityViolationException duplicate) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.SECRET_ALREADY_ISSUED);
        }
        messages.sendInvitation(email, workspaceId, invitationId, token.plaintext());
    }

    @Transactional
    public UUID acceptInvitation(String sessionToken, UUID workspaceId, UUID invitationId, String token) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        InvitationSnapshot invitation = store.lockInvitation(workspaceId, invitationId)
                .orElseThrow(() -> WorkspaceApplicationException.of(
                        WorkspaceApplicationException.Code.INVALID_CHALLENGE));
        if (invitation.consumedAt() != null || invitation.revokedAt() != null) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.CHALLENGE_CONSUMED);
        }
        if (!clock.instant().isBefore(invitation.expiresAt())
                || !MessageDigest.isEqual(invitation.tokenDigest(), identities.tokenDigest(token))
                || !MessageDigest.isEqual(invitation.emailDigest(), principal.emailDigest())) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.INVALID_CHALLENGE);
        }
        return store.acceptInvitation(invitation, principal.accountId(), clock.instant());
    }

    @Transactional
    public void changeRole(String sessionToken, UUID workspaceId, UUID membershipId, WorkspaceRole role) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        MembershipSnapshot actor = findActiveByAccount(workspaceId, principal.accountId());
        MembershipSnapshot target = lockActiveMembership(workspaceId, membershipId);
        try {
            policy.requireRoleChangeAllowed(actor.role(), target.role(), role, principal.isFresh(clock.instant()));
        } catch (MembershipRuleViolation failure) {
            throw map(failure);
        }
        store.changeRole(workspaceId, membershipId, role);
    }

    @Transactional
    public void revokeMembership(String sessionToken, UUID workspaceId, UUID membershipId) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        MembershipSnapshot actor = findActiveByAccount(workspaceId, principal.accountId());
        MembershipSnapshot target = lockActiveMembership(workspaceId, membershipId);
        boolean assignmentsResolved = !store.hasAssignments(workspaceId, membershipId);
        try {
            policy.requireRevocationAllowed(actor.role(), target.role(), assignmentsResolved,
                    principal.isFresh(clock.instant()));
        } catch (MembershipRuleViolation failure) {
            throw map(failure);
        }
        store.revokeMembership(workspaceId, membershipId, clock.instant());
    }

    public void revokeInvitation(String sessionToken, UUID workspaceId, UUID invitationId) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        MembershipSnapshot actor = findActiveByAccount(workspaceId, principal.accountId());
        if (actor.role() != WorkspaceRole.OWNER && actor.role() != WorkspaceRole.ADMIN) deny();
        store.revokeInvitation(workspaceId, invitationId, clock.instant());
    }

    @Transactional
    public UUID startOwnershipTransfer(String sessionToken, UUID workspaceId, UUID targetMembershipId) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        MembershipSnapshot actor = findActiveByAccount(workspaceId, principal.accountId());
        MembershipSnapshot target = lockActiveMembership(workspaceId, targetMembershipId);
        if (actor.role() != WorkspaceRole.OWNER || actor.membershipId().equals(target.membershipId())) deny();
        requireFresh(principal);
        try {
            return store.startOwnershipTransfer(workspaceId, actor.membershipId(), target.membershipId(),
                    clock.instant().plus(OWNERSHIP_TRANSFER_LIFETIME));
        } catch (DataIntegrityViolationException duplicate) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.SECRET_ALREADY_ISSUED);
        }
    }

    @Transactional
    public void acceptOwnershipTransfer(String sessionToken, UUID workspaceId, UUID transferId) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        MembershipSnapshot actor = findActiveByAccount(workspaceId, principal.accountId());
        OwnershipTransferSnapshot transfer = store.lockOwnershipTransfer(workspaceId, transferId)
                .orElseThrow(() -> WorkspaceApplicationException.of(
                        WorkspaceApplicationException.Code.INVALID_CHALLENGE));
        if (!"pending".equals(transfer.status()) || !clock.instant().isBefore(transfer.expiresAt())) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.CHALLENGE_CONSUMED);
        }
        if (!actor.membershipId().equals(transfer.toMembershipId())) deny();
        requireFresh(principal);
        store.acceptOwnershipTransfer(transfer, clock.instant());
    }

    private MembershipSnapshot findActiveByAccount(UUID workspaceId, UUID accountId) {
        return store.findMembership(workspaceId, accountId)
                .filter(membership -> "active".equals(membership.status()))
                .orElseThrow(() -> WorkspaceApplicationException.of(
                        WorkspaceApplicationException.Code.AUTHORIZATION_DENIED));
    }

    private MembershipSnapshot lockActiveMembership(UUID workspaceId, UUID membershipId) {
        return store.lockMembership(workspaceId, membershipId)
                .filter(membership -> "active".equals(membership.status()))
                .orElseThrow(() -> WorkspaceApplicationException.of(
                        WorkspaceApplicationException.Code.RESOURCE_NOT_FOUND));
    }

    private static void requireInvitationRole(WorkspaceRole actor, WorkspaceRole requested) {
        if (requested == WorkspaceRole.OWNER || (actor != WorkspaceRole.OWNER && actor != WorkspaceRole.ADMIN)
                || (actor == WorkspaceRole.ADMIN && requested == WorkspaceRole.ADMIN)) deny();
    }

    private static WorkspaceApplicationException map(MembershipRuleViolation failure) {
        return switch (failure.reason()) {
            case NOT_AUTHORIZED -> WorkspaceApplicationException.of(
                    WorkspaceApplicationException.Code.AUTHORIZATION_DENIED);
            case FRESH_AUTHENTICATION_REQUIRED -> WorkspaceApplicationException.of(
                    WorkspaceApplicationException.Code.AUTH_FRESHNESS_REQUIRED);
            case OWNERSHIP_TRANSFER_REQUIRED -> WorkspaceApplicationException.of(
                    WorkspaceApplicationException.Code.OWNERSHIP_TRANSFER_REQUIRED);
            case ACTIVE_ASSIGNMENT_REMAINS -> WorkspaceApplicationException.of(
                    WorkspaceApplicationException.Code.ACTIVE_ASSIGNMENT_REMAINS);
        };
    }

    private static void deny() {
        throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.AUTHORIZATION_DENIED);
    }

    private void requireFresh(AuthenticatedPrincipal principal) {
        if (!principal.isFresh(clock.instant())) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.AUTH_FRESHNESS_REQUIRED);
        }
    }

    public record WorkspaceCreated(UUID workspaceId, UUID ownerMembershipId) {}
}
