package com.stageaccord.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record RecoveryCase(UUID id, UUID accountId, String method, String status,
        Instant notBefore, Instant completedAt) {}
