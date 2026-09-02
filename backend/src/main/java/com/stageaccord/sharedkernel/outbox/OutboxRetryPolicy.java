package com.stageaccord.sharedkernel.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class OutboxRetryPolicy {
    private static final int MAXIMUM_ATTEMPTS = 10;
    private static final Duration MAXIMUM_AGE = Duration.ofHours(24);
    private static final List<Duration> DELAYS = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(12));

    public boolean mustIsolate(OutboxLease lease, Instant failedAt, boolean permanent) {
        return permanent
                || lease.attemptCount() >= MAXIMUM_ATTEMPTS
                || !failedAt.isBefore(lease.occurredAt().plus(MAXIMUM_AGE));
    }

    public Instant nextAttempt(OutboxLease lease, Instant failedAt) {
        int delayIndex = Math.min(lease.attemptCount() - 1, DELAYS.size() - 1);
        return failedAt.plus(DELAYS.get(delayIndex));
    }
}
