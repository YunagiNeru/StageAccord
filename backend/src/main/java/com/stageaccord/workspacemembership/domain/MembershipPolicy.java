package com.stageaccord.workspacemembership.domain;

import java.util.Objects;

public final class MembershipPolicy {
    public void requireRoleChangeAllowed(WorkspaceRole actorRole, WorkspaceRole currentRole,
            WorkspaceRole requestedRole, boolean freshAuthentication) {
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(currentRole, "currentRole");
        Objects.requireNonNull(requestedRole, "requestedRole");
        if (!freshAuthentication) deny(Reason.FRESH_AUTHENTICATION_REQUIRED);
        if (actorRole != WorkspaceRole.OWNER && actorRole != WorkspaceRole.ADMIN) deny(Reason.NOT_AUTHORIZED);
        if (currentRole == WorkspaceRole.OWNER || requestedRole == WorkspaceRole.OWNER) {
            deny(Reason.OWNERSHIP_TRANSFER_REQUIRED);
        }
        if (actorRole == WorkspaceRole.ADMIN
                && (currentRole == WorkspaceRole.ADMIN || requestedRole == WorkspaceRole.ADMIN)) {
            deny(Reason.NOT_AUTHORIZED);
        }
    }

    public void requireRevocationAllowed(WorkspaceRole actorRole, WorkspaceRole targetRole,
            boolean assignmentsResolved, boolean freshAuthentication) {
        requireRoleChangeAllowed(actorRole, targetRole, targetRole, freshAuthentication);
        if (!assignmentsResolved) deny(Reason.ACTIVE_ASSIGNMENT_REMAINS);
    }

    public boolean canAccessProjectData(WorkspaceRole role, boolean explicitlyAssigned) {
        return switch (Objects.requireNonNull(role, "role")) {
            case OWNER, ADMIN -> true;
            case PROJECT_MANAGER, MEMBER -> explicitlyAssigned;
            case BILLING_ADMIN -> false;
        };
    }

    private static void deny(Reason reason) { throw MembershipRuleViolation.of(reason); }

    public enum Reason {
        NOT_AUTHORIZED,
        FRESH_AUTHENTICATION_REQUIRED,
        OWNERSHIP_TRANSFER_REQUIRED,
        ACTIVE_ASSIGNMENT_REMAINS
    }
}
