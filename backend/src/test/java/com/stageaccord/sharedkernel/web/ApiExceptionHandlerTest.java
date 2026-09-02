package com.stageaccord.sharedkernel.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import com.stageaccord.sharedkernel.application.CommandRejectedException;
import com.stageaccord.sharedkernel.application.RejectionCode;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void stableBusinessFailureKeepsStatusCodeAndCorrelationId() {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY,
                "00000000-0000-0000-0000-000000000001")) {
            var detail = handler.handleApiFailure(ApiFailure.of(HttpStatus.FORBIDDEN, "PROJECT_FORBIDDEN"));

            assertThat(detail.getStatus()).isEqualTo(403);
            assertThat(detail.getProperties()).containsEntry("code", "PROJECT_FORBIDDEN")
                    .containsEntry("correlationId", "00000000-0000-0000-0000-000000000001");
        }
    }

    @Test
    void infrastructureAndConflictRejectionsDoNotCollapseIntoSuccess() {
        assertThat(handler.handleCommandRejection(CommandRejectedException.of(RejectionCode.DATABASE_UNAVAILABLE)).getStatus())
                .isEqualTo(503);
        assertThat(handler.handleCommandRejection(CommandRejectedException.of(RejectionCode.VERSION_CONFLICT)).getStatus())
                .isEqualTo(409);
        assertThat(handler.handleCommandRejection(CommandRejectedException.of(RejectionCode.IDEMPOTENCY_KEY_REQUIRED)).getStatus())
                .isEqualTo(400);
    }

    @Test
    void unexpectedFailureReturnsNoExceptionDetail() {
        var detail = handler.handleUnexpected(new IllegalStateException("secret material"));

        assertThat(detail.getStatus()).isEqualTo(500);
        assertThat(detail.getDetail()).isNull();
        assertThat(detail.getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(detail.toString()).doesNotContain("secret material");
    }
}
