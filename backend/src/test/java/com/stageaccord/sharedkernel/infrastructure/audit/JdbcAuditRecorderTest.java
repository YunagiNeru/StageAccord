package com.stageaccord.sharedkernel.infrastructure.audit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import com.stageaccord.sharedkernel.web.CorrelationIdFilter;

class JdbcAuditRecorderTest {
    @Test
    void appendsOnlyIdentifiersAndUsesTheRequestCorrelationId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("CreateWorkspace"),
                eq(workspaceId), eq(actorId), eq(correlationId))).thenReturn(1);

        try (MDC.MDCCloseable ignored = MDC.putCloseable(
                CorrelationIdFilter.MDC_KEY, correlationId.toString())) {
            new JdbcAuditRecorder(jdbc).recordAllowed("CreateWorkspace", actorId, workspaceId);
        }

        verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq("CreateWorkspace"),
                eq(workspaceId), eq(actorId), eq(correlationId));
    }
}
