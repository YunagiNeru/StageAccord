package com.stageaccord.workspacemembership;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.workspacemembership.api.WorkspaceAccessGateway;
import com.stageaccord.workspacemembership.application.WorkspaceApplicationException;
import com.stageaccord.workspacemembership.application.WorkspaceStore;
import com.stageaccord.workspacemembership.domain.WorkspaceRole;

@Component
final class DefaultWorkspaceAccessGateway implements WorkspaceAccessGateway {
    private final IdentityAccessGateway identities;
    private final WorkspaceStore workspaces;

    DefaultWorkspaceAccessGateway(IdentityAccessGateway identities, WorkspaceStore workspaces) {
        this.identities = identities;
        this.workspaces = workspaces;
    }

    @Override
    public AuthenticatedPrincipal requireMember(String sessionToken, UUID workspaceId, Set<WorkspaceRole> roles) {
        AuthenticatedPrincipal principal = identities.resolve(sessionToken);
        var membership = workspaces.findMembership(workspaceId, principal.accountId())
                .filter(item -> "active".equals(item.status()))
                .filter(item -> roles.contains(item.role()))
                .orElseThrow(() -> WorkspaceApplicationException.of(
                        WorkspaceApplicationException.Code.AUTHORIZATION_DENIED));
        return principal;
    }
}
