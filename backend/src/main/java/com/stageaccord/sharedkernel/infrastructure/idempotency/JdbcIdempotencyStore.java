package com.stageaccord.sharedkernel.infrastructure.idempotency;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.sharedkernel.application.CommandRejectedException;
import com.stageaccord.sharedkernel.application.RejectionCode;
import com.stageaccord.sharedkernel.idempotency.IdempotencyFingerprint;
import com.stageaccord.sharedkernel.idempotency.IdempotencyReservation;
import com.stageaccord.sharedkernel.idempotency.IdempotencyStore;

@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {
    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public IdempotencyReservation reserve(IdempotencyFingerprint fingerprint, Instant now, Instant expiresAt) {
        if (expiresAt.isBefore(now.plus(Duration.ofHours(24)))) {
            throw new IllegalArgumentException("idempotency retention must be at least 24 hours");
        }
        jdbc.update("""
                DELETE FROM infra.idempotency_record
                WHERE scope_hash = ? AND key_hash = ? AND expires_at <= ?
                """, fingerprint.scopeHash(), fingerprint.keyHash(), Timestamp.from(now));
        int inserted = jdbc.update("""
                INSERT INTO infra.idempotency_record (
                    scope_hash, key_hash, request_hash, state, status_code, response_ciphertext,
                    created_at, expires_at
                ) VALUES (?, ?, ?, 'pending', NULL, NULL, ?, ?)
                ON CONFLICT (scope_hash, key_hash) DO NOTHING
                """, fingerprint.scopeHash(), fingerprint.keyHash(), fingerprint.requestHash(),
                Timestamp.from(now), Timestamp.from(expiresAt));
        if (inserted == 1) return new IdempotencyReservation.Reserved();

        return jdbc.queryForObject("""
                SELECT request_hash, state, status_code, response_ciphertext
                FROM infra.idempotency_record
                WHERE scope_hash = ? AND key_hash = ?
                """, (result, rowNumber) -> {
                    if (!MessageDigest.isEqual(fingerprint.requestHash(), result.getBytes("request_hash"))) {
                        throw CommandRejectedException.of(RejectionCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    if ("pending".equals(result.getString("state"))) {
                        return new IdempotencyReservation.InProgress();
                    }
                    return new IdempotencyReservation.Replayed(
                            result.getInt("status_code"), result.getBytes("response_ciphertext"));
                }, fingerprint.scopeHash(), fingerprint.keyHash());
    }

    @Override
    public void complete(IdempotencyFingerprint fingerprint, int statusCode, byte[] responseCiphertext) {
        if (statusCode < 200 || statusCode > 599) throw new IllegalArgumentException("invalid statusCode");
        if (responseCiphertext == null || responseCiphertext.length == 0) {
            throw new IllegalArgumentException("responseCiphertext must not be empty");
        }
        int updated = jdbc.update("""
                UPDATE infra.idempotency_record
                SET state = 'completed', status_code = ?, response_ciphertext = ?
                WHERE scope_hash = ? AND key_hash = ? AND request_hash = ? AND state = 'pending'
                """, statusCode, responseCiphertext, fingerprint.scopeHash(), fingerprint.keyHash(),
                fingerprint.requestHash());
        if (updated != 1) throw CommandRejectedException.of(RejectionCode.IDEMPOTENCY_IN_PROGRESS);
    }
}
