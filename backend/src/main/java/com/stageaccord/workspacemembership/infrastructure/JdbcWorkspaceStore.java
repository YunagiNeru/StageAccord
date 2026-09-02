package com.stageaccord.workspacemembership.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.stageaccord.workspacemembership.application.InvitationSnapshot;
import com.stageaccord.workspacemembership.application.MembershipSnapshot;
import com.stageaccord.workspacemembership.application.OwnershipTransferSnapshot;
import com.stageaccord.workspacemembership.application.WorkspaceStore;
import com.stageaccord.workspacemembership.domain.WorkspaceRole;

@Repository
public class JdbcWorkspaceStore implements WorkspaceStore {
    private final JdbcTemplate jdbc;

    public JdbcWorkspaceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createWorkspace(UUID workspaceId, UUID ownerAccountId, UUID ownerMembershipId,
            String name, Instant now) {
        requireSingle(jdbc.update("""
                INSERT INTO workspace.workspace (id, owner_account_id, name, status, billing_mode)
                VALUES (?, ?, ?, 'active', 'trial')
                """, workspaceId, ownerAccountId, name));
        requireSingle(jdbc.update("""
                INSERT INTO workspace.membership (
                    workspace_id, id, account_id, role, status, joined_at
                ) VALUES (?, ?, ?, 'owner', 'active', ?)
                """, workspaceId, ownerMembershipId, ownerAccountId, Timestamp.from(now)));
    }

    @Override
    public Optional<MembershipSnapshot> findMembership(UUID workspaceId, UUID accountId) {
        return jdbc.query("""
                SELECT workspace_id, id, account_id, role, status FROM workspace.membership
                WHERE workspace_id = ? AND account_id = ?
                """, this::mapMembership, workspaceId, accountId).stream().findFirst();
    }

    @Override
    public Optional<MembershipSnapshot> lockMembership(UUID workspaceId, UUID membershipId) {
        return jdbc.query("""
                SELECT workspace_id, id, account_id, role, status FROM workspace.membership
                WHERE workspace_id = ? AND id = ? FOR UPDATE
                """, this::mapMembership, workspaceId, membershipId).stream().findFirst();
    }

    @Override
    public void createInvitation(InvitationSnapshot invitation) {
        requireSingle(jdbc.update("""
                INSERT INTO workspace.invitation (
                    workspace_id, id, token_digest, digest_key_id, email_digest_v2, role, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, invitation.workspaceId(), invitation.invitationId(), invitation.tokenDigest(),
                invitation.digestKeyId(), invitation.emailDigest(), databaseRole(invitation.role()),
                Timestamp.from(invitation.expiresAt())));
    }

    @Override
    public Optional<InvitationSnapshot> lockInvitation(UUID workspaceId, UUID invitationId) {
        return jdbc.query("""
                SELECT workspace_id, id, token_digest, digest_key_id, email_digest_v2, role,
                       expires_at, consumed_at, revoked_at
                FROM workspace.invitation WHERE workspace_id = ? AND id = ? FOR UPDATE
                """, this::mapInvitation, workspaceId, invitationId).stream().findFirst();
    }

    @Override
    public UUID acceptInvitation(InvitationSnapshot invitation, UUID accountId, Instant acceptedAt) {
        UUID membershipId = UUID.randomUUID();
        requireSingle(jdbc.update("""
                UPDATE workspace.invitation SET consumed_at = ?
                WHERE workspace_id = ? AND id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """, Timestamp.from(acceptedAt), invitation.workspaceId(), invitation.invitationId()));
        requireSingle(jdbc.update("""
                INSERT INTO workspace.membership (
                    workspace_id, id, account_id, role, status, joined_at
                ) VALUES (?, ?, ?, ?, 'active', ?)
                """, invitation.workspaceId(), membershipId, accountId,
                databaseRole(invitation.role()), Timestamp.from(acceptedAt)));
        return membershipId;
    }

    @Override
    public void revokeInvitation(UUID workspaceId, UUID invitationId, Instant revokedAt) {
        int updated = jdbc.update("""
                UPDATE workspace.invitation SET revoked_at = ?
                WHERE workspace_id = ? AND id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), workspaceId, invitationId);
        if (updated > 1) throw new IllegalStateException("multiple invitations updated");
    }

    @Override
    public void changeRole(UUID workspaceId, UUID membershipId, WorkspaceRole role) {
        requireSingle(jdbc.update("""
                UPDATE workspace.membership SET role = ?
                WHERE workspace_id = ? AND id = ? AND status = 'active'
                """, databaseRole(role), workspaceId, membershipId));
    }

    @Override
    public boolean hasAssignments(UUID workspaceId, UUID membershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM project.assignment
                    WHERE workspace_id = ? AND member_id = ? AND status = 'active'
                )
                """, Boolean.class, workspaceId, membershipId));
    }

    @Override
    public void revokeMembership(UUID workspaceId, UUID membershipId, Instant revokedAt) {
        requireSingle(jdbc.update("""
                UPDATE workspace.membership SET status = 'revoked', revoked_at = ?
                WHERE workspace_id = ? AND id = ? AND status = 'active'
                """, Timestamp.from(revokedAt), workspaceId, membershipId));
    }

    @Override
    public UUID startOwnershipTransfer(UUID workspaceId, UUID fromMembershipId,
            UUID toMembershipId, Instant expiresAt) {
        UUID transferId = UUID.randomUUID();
        requireSingle(jdbc.update("""
                INSERT INTO workspace.ownership_transfer (
                    workspace_id, id, from_membership_id, to_membership_id, status, expires_at
                ) VALUES (?, ?, ?, ?, 'pending', ?)
                """, workspaceId, transferId, fromMembershipId, toMembershipId, Timestamp.from(expiresAt)));
        return transferId;
    }

    @Override
    public Optional<OwnershipTransferSnapshot> lockOwnershipTransfer(UUID workspaceId, UUID transferId) {
        return jdbc.query("""
                SELECT workspace_id, id, from_membership_id, to_membership_id, status, expires_at
                FROM workspace.ownership_transfer WHERE workspace_id = ? AND id = ? FOR UPDATE
                """, (result, row) -> new OwnershipTransferSnapshot(
                        result.getObject("workspace_id", UUID.class), result.getObject("id", UUID.class),
                        result.getObject("from_membership_id", UUID.class),
                        result.getObject("to_membership_id", UUID.class), result.getString("status"),
                        result.getTimestamp("expires_at").toInstant()), workspaceId, transferId)
                .stream().findFirst();
    }

    @Override
    public void acceptOwnershipTransfer(OwnershipTransferSnapshot transfer, Instant acceptedAt) {
        MembershipSnapshot target = lockMembership(transfer.workspaceId(), transfer.toMembershipId())
                .orElseThrow(() -> new IllegalStateException("ownership target not found"));
        requireSingle(jdbc.update("""
                UPDATE workspace.membership SET role = 'admin'
                WHERE workspace_id = ? AND id = ? AND role = 'owner' AND status = 'active'
                """, transfer.workspaceId(), transfer.fromMembershipId()));
        requireSingle(jdbc.update("""
                UPDATE workspace.membership SET role = 'owner'
                WHERE workspace_id = ? AND id = ? AND status = 'active'
                """, transfer.workspaceId(), transfer.toMembershipId()));
        requireSingle(jdbc.update("""
                UPDATE workspace.workspace SET owner_account_id = ?, version = version + 1
                WHERE id = ?
                """, target.accountId(), transfer.workspaceId()));
        requireSingle(jdbc.update("""
                UPDATE workspace.ownership_transfer SET status = 'accepted'
                WHERE workspace_id = ? AND id = ? AND status = 'pending'
                """, transfer.workspaceId(), transfer.transferId()));
    }

    private MembershipSnapshot mapMembership(ResultSet result, int row) throws SQLException {
        return new MembershipSnapshot(result.getObject("workspace_id", UUID.class),
                result.getObject("id", UUID.class), result.getObject("account_id", UUID.class),
                WorkspaceRole.valueOf(result.getString("role").toUpperCase(java.util.Locale.ROOT)),
                result.getString("status"));
    }

    private InvitationSnapshot mapInvitation(ResultSet result, int row) throws SQLException {
        return new InvitationSnapshot(result.getObject("workspace_id", UUID.class),
                result.getObject("id", UUID.class), result.getBytes("token_digest"),
                result.getString("digest_key_id"), result.getBytes("email_digest_v2"),
                WorkspaceRole.valueOf(result.getString("role").toUpperCase(java.util.Locale.ROOT)),
                result.getTimestamp("expires_at").toInstant(), instant(result.getTimestamp("consumed_at")),
                instant(result.getTimestamp("revoked_at")));
    }

    private static String databaseRole(WorkspaceRole role) {
        return role.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static void requireSingle(int updated) {
        if (updated != 1) throw new IllegalStateException("workspace state changed concurrently");
    }
}
