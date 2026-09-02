package com.stageaccord.sharedkernel.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CommandContext(
        UUID actorId,
        Optional<UUID> delegatedActorId,
        UUID workspaceId,
        UUID correlationId,
        Optional<String> idempotencyKey,
        Optional<Long> expectedVersion) {

    public CommandContext {
        Objects.requireNonNull(actorId, "actorId");
        delegatedActorId = Objects.requireNonNull(delegatedActorId, "delegatedActorId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(correlationId, "correlationId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").map(String::strip);
        expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion");
        if (idempotencyKey.isPresent() && idempotencyKey.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (expectedVersion.isPresent() && expectedVersion.orElseThrow() < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    public void requireFor(CommandClass commandClass) {
        Objects.requireNonNull(commandClass, "commandClass");
        if (commandClass.isWrite() && idempotencyKey.isEmpty()) {
            throw CommandRejectedException.of(RejectionCode.IDEMPOTENCY_KEY_REQUIRED);
        }
    }
}
