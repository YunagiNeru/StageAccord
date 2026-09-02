package com.stageaccord.sharedkernel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.stageaccord.sharedkernel.application.CommandClass;
import com.stageaccord.sharedkernel.application.CommandRejectedException;
import com.stageaccord.sharedkernel.application.RejectionCode;

class RuntimeGuardTest {
    private final RuntimeGuard guard = new RuntimeGuard();

    @Test
    void privateOperationFailsClosedWhenSessionStoreCannotBeEvaluated() {
        RuntimeSnapshot snapshot = healthy(RuntimeMode.RESTRICTED, false, true, true, true);

        assertRejected(CommandClass.PRIVATE_QUERY, snapshot, RejectionCode.SESSION_VALIDATION_UNAVAILABLE);
    }

    @Test
    void writeFailsClosedWhenAuditCannotBeAppended() {
        RuntimeSnapshot snapshot = healthy(RuntimeMode.NORMAL, true, false, true, true);

        assertRejected(CommandClass.GENERAL_COMMAND, snapshot, RejectionCode.AUDIT_UNAVAILABLE);
    }

    @Test
    void writeStoppedModeKeepsPublicReadButRejectsWrites() {
        RuntimeSnapshot snapshot = healthy(RuntimeMode.WRITE_STOPPED, true, true, true, true);

        guard.requireAllowed(CommandClass.PUBLIC_QUERY, snapshot);
        assertRejected(CommandClass.GENERAL_COMMAND, snapshot, RejectionCode.FEATURE_WRITE_STOPPED);
    }

    @Test
    void fileCommandRequiresObjectStorage() {
        RuntimeSnapshot snapshot = healthy(RuntimeMode.RESTRICTED, true, true, false, true);

        assertRejected(CommandClass.FILE_COMMAND, snapshot, RejectionCode.OBJECT_STORAGE_UNAVAILABLE);
    }

    @Test
    void timeSensitiveCommandRejectsUntrustedClock() {
        RuntimeSnapshot snapshot = new RuntimeSnapshot(RuntimeMode.RESTRICTED, true, true, true, true, true,
                Duration.ofMillis(2001));

        assertRejected(CommandClass.TIME_SENSITIVE_COMMAND, snapshot, RejectionCode.TIME_TRUST_UNAVAILABLE);
    }

    private RuntimeSnapshot healthy(RuntimeMode mode, boolean session, boolean audit, boolean storage,
            boolean feature) {
        return new RuntimeSnapshot(mode, true, session, audit, storage, feature, Duration.ZERO);
    }

    private void assertRejected(CommandClass commandClass, RuntimeSnapshot snapshot, RejectionCode expected) {
        assertThatThrownBy(() -> guard.requireAllowed(commandClass, snapshot))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }
}
