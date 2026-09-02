package com.stageaccord.sharedkernel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CommandContextTest {
    private final UUID actorId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @Test
    void writeRequiresIdempotencyKey() {
        CommandContext context = new CommandContext(actorId, Optional.empty(), workspaceId, correlationId,
                Optional.empty(), Optional.of(1L));

        assertThatThrownBy(() -> context.requireFor(CommandClass.GENERAL_COMMAND))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        error -> assertThat(error.code()).isEqualTo(RejectionCode.IDEMPOTENCY_KEY_REQUIRED));
    }

    @Test
    void queryDoesNotRequireIdempotencyKey() {
        CommandContext context = new CommandContext(actorId, Optional.empty(), workspaceId, correlationId,
                Optional.empty(), Optional.empty());

        context.requireFor(CommandClass.PRIVATE_QUERY);
    }

    @Test
    void negativeVersionIsRejectedAtBoundary() {
        assertThatThrownBy(() -> new CommandContext(actorId, Optional.empty(), workspaceId, correlationId,
                Optional.of("key"), Optional.of(-1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleVersionIsRejected() {
        assertThatThrownBy(() -> OptimisticLockGuard.requireVersion(4, 3))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        error -> assertThat(error.code()).isEqualTo(RejectionCode.VERSION_CONFLICT));
    }
}
