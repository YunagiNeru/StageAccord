package com.stageaccord.sharedkernel.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class OutboxWorker {
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final String workerId;
    private final OutboxStore store;
    private final OutboxDelivery delivery;
    private final OutboxRetryPolicy retryPolicy;
    private final Clock clock;

    public OutboxWorker(String workerId, OutboxStore store, OutboxDelivery delivery,
            OutboxRetryPolicy retryPolicy, Clock clock) {
        this.workerId = requireText(workerId, "workerId");
        this.store = Objects.requireNonNull(store, "store");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean runOnce() {
        var lease = store.claimNext(workerId, clock.instant(), LEASE_DURATION);
        if (lease.isEmpty()) return false;

        try {
            delivery.deliver(lease.orElseThrow());
            store.markDelivered(lease.orElseThrow().eventId());
        } catch (DeliveryFailure failure) {
            handleFailure(lease.orElseThrow(), failure);
        }
        return true;
    }

    private void handleFailure(OutboxLease lease, DeliveryFailure failure) {
        Instant failedAt = clock.instant();
        String errorClass = failure.getClass().getSimpleName();
        if (retryPolicy.mustIsolate(lease, failedAt, failure.isPermanent())) {
            store.isolate(lease, failedAt, errorClass);
            return;
        }
        store.reschedule(lease.eventId(), retryPolicy.nextAttempt(lease, failedAt), errorClass);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.strip();
    }
}
