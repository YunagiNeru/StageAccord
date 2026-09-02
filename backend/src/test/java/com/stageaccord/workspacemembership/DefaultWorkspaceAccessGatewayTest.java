package com.stageaccord.workspacemembership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.workspacemembership.application.MembershipSnapshot;
import com.stageaccord.workspacemembership.api.WorkspaceAccess;
import com.stageaccord.workspacemembership.application.WorkspaceApplicationException;
import com.stageaccord.workspacemembership.application.WorkspaceStore;
import com.stageaccord.workspacemembership.domain.WorkspaceRole;

class DefaultWorkspaceAccessGatewayTest {
    @Test
    void requiresAnActiveMembershipWithAnAllowedRole() {
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        var principal = new AuthenticatedPrincipal(accountId, new byte[32], "password_totp", Instant.now());
        IdentityAccessGateway identities = mock(IdentityAccessGateway.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(identities.resolve("session")).thenReturn(principal);
        when(workspaces.findMembership(workspaceId, accountId)).thenReturn(Optional.of(
                new MembershipSnapshot(workspaceId, UUID.randomUUID(), accountId,
                        WorkspaceRole.MEMBER, "active")));
        var gateway = new DefaultWorkspaceAccessGateway(identities, workspaces);

        assertThat(gateway.requireMember("session", workspaceId, Set.of(WorkspaceAccess.MEMBER)))
                .isEqualTo(principal);
        assertThatThrownBy(() -> gateway.requireMember("session", workspaceId, Set.of(WorkspaceAccess.OWNER)))
                .isInstanceOf(WorkspaceApplicationException.class);
    }
}
