package com.stageaccord.sharedkernel.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OutboxStore {
    Optional<OutboxLease> claimNext(String workerId, Instant now, Duration leaseDuration);

    void markDelivered(UUID eventId);

    void reschedule(UUID eventId, Instant availableAt, String errorClass);

    void isolate(OutboxLease lease, Instant failedAt, String errorClass);
}
