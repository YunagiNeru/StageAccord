package com.stageaccord.sharedkernel.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OutboxWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    @Test
    void successfulDeliveryIsMarkedOnce() {
        FakeStore store = new FakeStore(lease(1, NOW.minusSeconds(1)));
        OutboxWorker worker = worker(store, ignored -> {});

        assertThat(worker.runOnce()).isTrue();
        assertThat(store.delivered).isTrue();
        assertThat(store.claimDuration).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void transientFailureIsRescheduledWithoutCyclingDelay() {
        FakeStore store = new FakeStore(lease(7, NOW.minus(Duration.ofHours(1))));
        OutboxWorker worker = worker(store, ignored -> { throw new DeliveryFailure("temporary", false); });

        worker.runOnce();

        assertThat(store.nextAttempt).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(store.isolated).isFalse();
    }

    @Test
    void permanentFailureIsIsolatedImmediately() {
        FakeStore store = new FakeStore(lease(1, NOW.minusSeconds(1)));
        OutboxWorker worker = worker(store, ignored -> { throw new DeliveryFailure("permanent", true); });

        worker.runOnce();

        assertThat(store.isolated).isTrue();
        assertThat(store.nextAttempt).isNull();
    }

    @Test
    void tenthAttemptIsIsolated() {
        FakeStore store = new FakeStore(lease(10, NOW.minus(Duration.ofHours(1))));
        OutboxWorker worker = worker(store, ignored -> { throw new DeliveryFailure("temporary", false); });

        worker.runOnce();

        assertThat(store.isolated).isTrue();
    }

    private OutboxWorker worker(FakeStore store, OutboxDelivery delivery) {
        return new OutboxWorker("worker-1", store, delivery, new OutboxRetryPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OutboxLease lease(int attempt, Instant occurredAt) {
        return new OutboxLease(UUID.randomUUID(), "test.event.v1", "{}", attempt, NOW, occurredAt, "test");
    }

    private static final class FakeStore implements OutboxStore {
        private final OutboxLease lease;
        private boolean delivered;
        private boolean isolated;
        private Instant nextAttempt;
        private Duration claimDuration;

        private FakeStore(OutboxLease lease) { this.lease = lease; }

        @Override
        public Optional<OutboxLease> claimNext(String workerId, Instant now, Duration leaseDuration) {
            claimDuration = leaseDuration;
            return Optional.of(lease);
        }

        @Override public void markDelivered(UUID eventId) { delivered = true; }
        @Override public void reschedule(UUID eventId, Instant availableAt, String errorClass) { nextAttempt = availableAt; }
        @Override public void isolate(OutboxLease value, Instant failedAt, String errorClass) { isolated = true; }
    }
}
