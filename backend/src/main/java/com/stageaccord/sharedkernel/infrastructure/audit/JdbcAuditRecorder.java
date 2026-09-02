package com.stageaccord.sharedkernel.infrastructure.audit;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.CorrelationIdFilter;

@Repository
public final class JdbcAuditRecorder implements AuditRecorder {
    private final JdbcTemplate jdbc;

    public JdbcAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordAllowed(String action, UUID actorId, UUID workspaceId) {
        UUID correlationId = correlationId();
        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM audit.append_event(
                    jsonb_build_object('action', ?, 'result', 'allowed', 'workspaceId', ?),
                    jsonb_build_object('principalId', ?),
                    ?
                )
                """, Integer.class, action, workspaceId, actorId, correlationId);
        if (rows == null || rows != 1) throw new IllegalStateException("audit append failed");
    }

    private static UUID correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (value == null) return UUID.randomUUID();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
