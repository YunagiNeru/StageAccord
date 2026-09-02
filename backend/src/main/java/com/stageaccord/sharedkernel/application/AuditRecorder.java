package com.stageaccord.sharedkernel.application;

import java.util.UUID;

public interface AuditRecorder {
    void recordAllowed(String action, UUID actorId, UUID workspaceId);
}
