package com.stageaccord.workspacemembership.api;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
public interface WorkspaceAccessGateway {
    AuthenticatedPrincipal requireMember(String sessionToken, UUID workspaceId, Set<WorkspaceAccess> roles);

    default AuthenticatedPrincipal requireMember(String sessionToken, UUID workspaceId) {
        return requireMember(sessionToken, workspaceId, EnumSet.allOf(WorkspaceAccess.class));
    }
}
