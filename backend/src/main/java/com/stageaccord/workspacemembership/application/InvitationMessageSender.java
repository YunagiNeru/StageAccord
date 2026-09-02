package com.stageaccord.workspacemembership.application;

import java.util.UUID;

public interface InvitationMessageSender {
    void sendInvitation(String email, UUID workspaceId, UUID invitationId, String token);
}
