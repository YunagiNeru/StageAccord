package com.stageaccord.sharedkernel.infrastructure.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.sharedkernel.outbox.OutboxLease;
import com.stageaccord.sharedkernel.outbox.OutboxStore;

@Repository
public class JdbcOutboxStore implements OutboxStore {
    private static final String CLAIM_SQL = """
            WITH candidate AS (
                SELECT event_id
                FROM infra.outbox_event event
                WHERE event.available_at <= ?
                  AND (event.status = 'pending' OR (event.status = 'leased' AND event.lease_until < ?))
                  AND NOT EXISTS (
                    SELECT 1 FROM infra.outbox_event prior
                    WHERE prior.producer = event.producer
                      AND prior.aggregate_type = event.aggregate_type
                      AND prior.aggregate_id = event.aggregate_id
                      AND prior.aggregate_sequence < event.aggregate_sequence
                      AND prior.status NOT IN ('delivered', 'skipped')
                  )
                ORDER BY event.occurred_at, event.event_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE infra.outbox_event event
            SET status = 'leased', leased_by = ?, lease_until = ?,
                attempt_count = event.attempt_count + 1,
                first_attempted_at = COALESCE(event.first_attempted_at, ?)
            FROM candidate
            WHERE event.event_id = candidate.event_id
            RETURNING event.event_id, event.event_type, event.payload::text, event.attempt_count,
                      event.first_attempted_at, event.occurred_at, event.producer
            """;

    private final JdbcTemplate jdbc;

    public JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<OutboxLease> claimNext(String workerId, Instant now, Duration leaseDuration) {
        var events = jdbc.query(CLAIM_SQL, this::mapLease,
                Timestamp.from(now), Timestamp.from(now), workerId,
                Timestamp.from(now.plus(leaseDuration)), Timestamp.from(now));
        return events.stream().findFirst();
    }

    @Override
    public void markDelivered(UUID eventId) {
        requireSingleUpdate("""
                UPDATE infra.outbox_event
                SET status = 'delivered', leased_by = NULL, lease_until = NULL, last_error_class = NULL
                WHERE event_id = ? AND status = 'leased'
                """, eventId);
    }

    @Override
    public void reschedule(UUID eventId, Instant availableAt, String errorClass) {
        requireSingleUpdate("""
                UPDATE infra.outbox_event
                SET status = 'pending', available_at = ?, leased_by = NULL, lease_until = NULL,
                    last_error_class = ?
                WHERE event_id = ? AND status = 'leased'
                """, Timestamp.from(availableAt), errorClass, eventId);
    }

    @Override
    @Transactional
    public void isolate(OutboxLease lease, Instant failedAt, String errorClass) {
        int inserted = jdbc.update("""
                INSERT INTO infra.outbox_dead_letter (
                    event_id, owner_module, error_class, redacted_message, attempt_count,
                    first_failed_at, last_failed_at, isolated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, lease.eventId(), lease.ownerModule(), errorClass, "delivery failed",
                lease.attemptCount(), Timestamp.from(lease.firstAttemptedAt()),
                Timestamp.from(failedAt), Timestamp.from(failedAt));
        if (inserted != 1) throw new IllegalStateException("Outbox dead letter already exists");
        requireSingleUpdate("""
                UPDATE infra.outbox_event
                SET status = 'dead_letter', leased_by = NULL, lease_until = NULL, last_error_class = ?
                WHERE event_id = ? AND status = 'leased'
                """, errorClass, lease.eventId());
    }

    private OutboxLease mapLease(ResultSet result, int rowNumber) throws SQLException {
        return new OutboxLease(
                result.getObject("event_id", UUID.class),
                result.getString("event_type"),
                result.getString("payload"),
                result.getInt("attempt_count"),
                result.getTimestamp("first_attempted_at").toInstant(),
                result.getTimestamp("occurred_at").toInstant(),
                result.getString("producer"));
    }

    private void requireSingleUpdate(String sql, Object... arguments) {
        if (jdbc.update(sql, arguments) != 1) {
            throw new IllegalStateException("Outbox lease is no longer owned");
        }
    }
}
