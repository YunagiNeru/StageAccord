package com.stageaccord.workspacemembership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.identityaccess.api.IssuedOpaqueToken;
import com.stageaccord.workspacemembership.application.InvitationMessageSender;
import com.stageaccord.workspacemembership.application.InvitationSnapshot;
import com.stageaccord.workspacemembership.application.MembershipSnapshot;
import com.stageaccord.workspacemembership.application.WorkspaceApplicationException;
import com.stageaccord.workspacemembership.application.WorkspaceStore;
import com.stageaccord.workspacemembership.domain.MembershipPolicy;
import com.stageaccord.workspacemembership.domain.WorkspaceRole;
import com.stageaccord.sharedkernel.application.AuditRecorder;

class WorkspaceMembershipServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final byte[] EMAIL_DIGEST = filled((byte) 4);
    private static final byte[] TOKEN_DIGEST = filled((byte) 8);

    private final WorkspaceStore store = mock(WorkspaceStore.class);
    private final IdentityAccessGateway identities = mock(IdentityAccessGateway.class);
    private final InvitationMessageSender messages = mock(InvitationMessageSender.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private WorkspaceMembershipService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        when(identities.resolve("session")).thenReturn(new AuthenticatedPrincipal(
                accountId, EMAIL_DIGEST, "password_totp", NOW.minusSeconds(60)));
        when(identities.issueToken()).thenReturn(new IssuedOpaqueToken(
                "invitation-token", TOKEN_DIGEST, "identity-v1"));
        when(identities.emailDigest(any())).thenReturn(EMAIL_DIGEST);
        when(identities.tokenDigest("invitation-token")).thenReturn(TOKEN_DIGEST);
        service = new WorkspaceMembershipService(store, identities, messages,
                new MembershipPolicy(), audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsWorkspaceAndOwnerMembershipAsOneUseCase() {
        var created = service.createWorkspace("session", "  Studio  ");

        verify(store).createWorkspace(created.workspaceId(), accountId,
                created.ownerMembershipId(), "Studio", NOW);
    }

    @Test
    void ownerCanIssueAnInvitationWithoutPersistingThePlaintextToken() {
        UUID workspaceId = UUID.randomUUID();
        UUID ownerMembershipId = UUID.randomUUID();
        when(store.findMembership(workspaceId, accountId)).thenReturn(Optional.of(
                membership(workspaceId, ownerMembershipId, accountId, WorkspaceRole.OWNER)));

        service.inviteMember("session", workspaceId, "invitee@example.com", WorkspaceRole.MEMBER);

        ArgumentCaptor<InvitationSnapshot> invitation = ArgumentCaptor.forClass(InvitationSnapshot.class);
        verify(store).createInvitation(invitation.capture());
        assertThat(invitation.getValue().tokenDigest()).isEqualTo(TOKEN_DIGEST);
        verify(messages).sendInvitation(eq("invitee@example.com"), eq(workspaceId),
                eq(invitation.getValue().invitationId()), eq("invitation-token"));
    }

    @Test
    void invitationRequiresTheBoundEmailAndSingleUseToken() {
        UUID workspaceId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        InvitationSnapshot invitation = new InvitationSnapshot(workspaceId, invitationId,
                TOKEN_DIGEST, "identity-v1", filled((byte) 7), WorkspaceRole.MEMBER,
                NOW.plusSeconds(60), null, null);
        when(store.lockInvitation(workspaceId, invitationId)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.acceptInvitation(
                "session", workspaceId, invitationId, "invitation-token"))
                .isInstanceOfSatisfying(WorkspaceApplicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                WorkspaceApplicationException.Code.INVALID_CHALLENGE));
        verify(store, never()).acceptInvitation(any(), any(), any());
    }

    @Test
    void directOwnerRoleMutationIsRejectedInFavorOfOwnershipTransfer() {
        UUID workspaceId = UUID.randomUUID();
        UUID ownerMembershipId = UUID.randomUUID();
        UUID targetMembershipId = UUID.randomUUID();
        when(store.findMembership(workspaceId, accountId)).thenReturn(Optional.of(
                membership(workspaceId, ownerMembershipId, accountId, WorkspaceRole.OWNER)));
        when(store.lockMembership(workspaceId, targetMembershipId)).thenReturn(Optional.of(
                membership(workspaceId, targetMembershipId, UUID.randomUUID(), WorkspaceRole.MEMBER)));

        assertThatThrownBy(() -> service.changeRole(
                "session", workspaceId, targetMembershipId, WorkspaceRole.OWNER))
                .isInstanceOfSatisfying(WorkspaceApplicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                WorkspaceApplicationException.Code.OWNERSHIP_TRANSFER_REQUIRED));
        verify(store, never()).changeRole(any(), any(), any());
    }

    @Test
    void membershipWithAnActiveAssignmentCannotBeRevoked() {
        UUID workspaceId = UUID.randomUUID();
        UUID ownerMembershipId = UUID.randomUUID();
        UUID targetMembershipId = UUID.randomUUID();
        when(store.findMembership(workspaceId, accountId)).thenReturn(Optional.of(
                membership(workspaceId, ownerMembershipId, accountId, WorkspaceRole.OWNER)));
        when(store.lockMembership(workspaceId, targetMembershipId)).thenReturn(Optional.of(
                membership(workspaceId, targetMembershipId, UUID.randomUUID(), WorkspaceRole.MEMBER)));
        when(store.hasAssignments(workspaceId, targetMembershipId)).thenReturn(true);

        assertThatThrownBy(() -> service.revokeMembership("session", workspaceId, targetMembershipId))
                .isInstanceOfSatisfying(WorkspaceApplicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                WorkspaceApplicationException.Code.ACTIVE_ASSIGNMENT_REMAINS));
        verify(store, never()).revokeMembership(any(), any(), any());
    }

    private static MembershipSnapshot membership(UUID workspaceId, UUID membershipId,
            UUID memberAccountId, WorkspaceRole role) {
        return new MembershipSnapshot(workspaceId, membershipId, memberAccountId, role, "active");
    }

    private static byte[] filled(byte value) {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, value);
        return digest;
    }
}
