package com.stageaccord.workspacemembership.api;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.workspacemembership.WorkspaceMembershipService;
import com.stageaccord.workspacemembership.application.WorkspaceApplicationException;
import com.stageaccord.workspacemembership.domain.WorkspaceRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@Profile("app")
@RequestMapping("/api/v1/workspaces")
public final class WorkspaceController {
    private static final String SESSION_COOKIE = "__Host-stageaccord-session";
    private final WorkspaceMembershipService workspaces;

    public WorkspaceController(WorkspaceMembershipService workspaces) {
        this.workspaces = workspaces;
    }

    @PostMapping
    public ResponseEntity<WorkspaceResponse> createWorkspace(@CookieValue(SESSION_COOKIE) String session,
            @Valid @RequestBody CreateWorkspaceRequest request) {
        var created = workspaces.createWorkspace(session, request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WorkspaceResponse(created.workspaceId(), created.ownerMembershipId()));
    }

    @PostMapping("/{workspaceId}/invitations")
    public ResponseEntity<Void> inviteMember(@CookieValue(SESSION_COOKIE) String session,
            @PathVariable UUID workspaceId, @Valid @RequestBody InviteMemberRequest request) {
        workspaces.inviteMember(session, workspaceId, request.email(), role(request.role()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{workspaceId}/invitations/{invitationId}/acceptances")
    public ResponseEntity<MembershipResponse> acceptInvitation(@CookieValue(SESSION_COOKIE) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID invitationId,
            @Valid @RequestBody TokenRequest request) {
        UUID membershipId = workspaces.acceptInvitation(session, workspaceId, invitationId, request.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(new MembershipResponse(membershipId));
    }

    @DeleteMapping("/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> revokeInvitation(@CookieValue(SESSION_COOKIE) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID invitationId) {
        workspaces.revokeInvitation(session, workspaceId, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{workspaceId}/members/{memberId}")
    public MembershipRoleResponse changeRole(@CookieValue(SESSION_COOKIE) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID memberId,
            @Valid @RequestBody ChangeRoleRequest request) {
        WorkspaceRole role = role(request.role());
        workspaces.changeRole(session, workspaceId, memberId, role);
        return new MembershipRoleResponse(memberId, role.name().toLowerCase(java.util.Locale.ROOT));
    }

    @DeleteMapping("/{workspaceId}/members/{memberId}")
    public ResponseEntity<Void> revokeMembership(@CookieValue(SESSION_COOKIE) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID memberId) {
        workspaces.revokeMembership(session, workspaceId, memberId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workspaceId}/ownership-transfers")
    public ResponseEntity<OwnershipTransferResponse> startOwnershipTransfer(
            @CookieValue(SESSION_COOKIE) String session, @PathVariable UUID workspaceId,
            @Valid @RequestBody OwnershipTransferRequest request) {
        UUID id = workspaces.startOwnershipTransfer(session, workspaceId, request.targetMembershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new OwnershipTransferResponse(id));
    }

    @PostMapping("/{workspaceId}/ownership-transfers/{id}/acceptances")
    public OwnershipTransferResponse acceptOwnershipTransfer(@CookieValue(SESSION_COOKIE) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID id) {
        workspaces.acceptOwnershipTransfer(session, workspaceId, id);
        return new OwnershipTransferResponse(id);
    }

    private static WorkspaceRole role(String role) {
        try {
            return WorkspaceRole.valueOf(role.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw WorkspaceApplicationException.of(WorkspaceApplicationException.Code.INVALID_REQUEST);
        }
    }

    public record CreateWorkspaceRequest(@NotBlank @Size(max = 160) String name) {}
    public record WorkspaceResponse(UUID workspaceId, UUID ownerMembershipId) {}
    public record InviteMemberRequest(@NotBlank @Email @Size(max = 320) String email,
            @NotBlank String role) {}
    public record TokenRequest(@NotBlank @Size(max = 256) String token) {}
    public record MembershipResponse(UUID membershipId) {}
    public record ChangeRoleRequest(@NotBlank String role) {}
    public record MembershipRoleResponse(UUID membershipId, String role) {}
    public record OwnershipTransferRequest(@NotNull UUID targetMembershipId) {}
    public record OwnershipTransferResponse(UUID transferId) {}
}
