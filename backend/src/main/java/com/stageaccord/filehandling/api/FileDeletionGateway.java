package com.stageaccord.filehandling.api;

import java.util.UUID;

public interface FileDeletionGateway {
    int deleteProjectFiles(UUID workspaceId);
}
