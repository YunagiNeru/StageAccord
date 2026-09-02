package com.stageaccord.workspacemembership.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.stageaccord.workspacemembership.WorkspaceMembershipService;

class WorkspaceControllerTest {
    private final WorkspaceMembershipService service = mock(WorkspaceMembershipService.class);
    private final WorkspaceController controller = new WorkspaceController(service);

    @Test
    void createWorkspaceReturnsOnlyTheNewPublicIdentifiers() {
        UUID workspaceId = UUID.randomUUID();
        UUID ownerMembershipId = UUID.randomUUID();
        when(service.createWorkspace("session", "Studio")).thenReturn(
                new WorkspaceMembershipService.WorkspaceCreated(workspaceId, ownerMembershipId));

        var response = controller.createWorkspace("session",
                new WorkspaceController.CreateWorkspaceRequest("Studio"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().workspaceId()).isEqualTo(workspaceId);
        assertThat(response.getBody().ownerMembershipId()).isEqualTo(ownerMembershipId);
    }

    @Test
    void inviteMemberDoesNotReturnTheSecretToken() {
        UUID workspaceId = UUID.randomUUID();

        var response = controller.inviteMember("session", workspaceId,
                new WorkspaceController.InviteMemberRequest("invitee@example.com", "member"));

        verify(service).inviteMember("session", workspaceId, "invitee@example.com",
                com.stageaccord.workspacemembership.domain.WorkspaceRole.MEMBER);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNull();
    }
}
