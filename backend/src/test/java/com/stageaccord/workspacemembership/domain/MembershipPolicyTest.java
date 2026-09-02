package com.stageaccord.workspacemembership.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MembershipPolicyTest {
    private final MembershipPolicy policy = new MembershipPolicy();

    @Test
    void ownerRoleCanOnlyChangeThroughOwnershipTransfer() {
        assertDenied(() -> policy.requireRoleChangeAllowed(
                WorkspaceRole.OWNER, WorkspaceRole.MEMBER, WorkspaceRole.OWNER, true),
                MembershipPolicy.Reason.OWNERSHIP_TRANSFER_REQUIRED);
        assertDenied(() -> policy.requireRoleChangeAllowed(
                WorkspaceRole.OWNER, WorkspaceRole.OWNER, WorkspaceRole.ADMIN, true),
                MembershipPolicy.Reason.OWNERSHIP_TRANSFER_REQUIRED);
    }

    @Test
    void adminCannotPromoteOrRevokeAnotherAdmin() {
        assertDenied(() -> policy.requireRoleChangeAllowed(
                WorkspaceRole.ADMIN, WorkspaceRole.MEMBER, WorkspaceRole.ADMIN, true),
                MembershipPolicy.Reason.NOT_AUTHORIZED);
        assertDenied(() -> policy.requireRevocationAllowed(
                WorkspaceRole.ADMIN, WorkspaceRole.ADMIN, true, true),
                MembershipPolicy.Reason.NOT_AUTHORIZED);
    }

    @Test
    void revocationRequiresFreshAuthenticationAndResolvedAssignments() {
        assertDenied(() -> policy.requireRevocationAllowed(
                WorkspaceRole.OWNER, WorkspaceRole.MEMBER, true, false),
                MembershipPolicy.Reason.FRESH_AUTHENTICATION_REQUIRED);
        assertDenied(() -> policy.requireRevocationAllowed(
                WorkspaceRole.OWNER, WorkspaceRole.MEMBER, false, true),
                MembershipPolicy.Reason.ACTIVE_ASSIGNMENT_REMAINS);
    }

    @Test
    void billingAdministratorNeverGetsProjectDataByRole() {
        assertThat(policy.canAccessProjectData(WorkspaceRole.BILLING_ADMIN, true)).isFalse();
        assertThat(policy.canAccessProjectData(WorkspaceRole.MEMBER, false)).isFalse();
        assertThat(policy.canAccessProjectData(WorkspaceRole.MEMBER, true)).isTrue();
    }

    private static void assertDenied(Runnable action, MembershipPolicy.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MembershipRuleViolation.class,
                        error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
