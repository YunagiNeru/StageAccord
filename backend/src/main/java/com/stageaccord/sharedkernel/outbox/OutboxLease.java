package com.stageaccord.sharedkernel.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxLease(
        UUID eventId,
        String eventType,
        String payload,
        int attemptCount,
        Instant firstAttemptedAt,
        Instant occurredAt,
        String ownerModule) {

    public OutboxLease {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(firstAttemptedAt, "firstAttemptedAt");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(ownerModule, "ownerModule");
        if (attemptCount < 1 || attemptCount > 10) {
            throw new IllegalArgumentException("attemptCount must be between 1 and 10");
        }
    }
}
