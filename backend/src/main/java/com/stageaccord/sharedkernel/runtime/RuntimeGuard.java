package com.stageaccord.sharedkernel.runtime;

import static com.stageaccord.sharedkernel.application.RejectionCode.AUDIT_UNAVAILABLE;
import static com.stageaccord.sharedkernel.application.RejectionCode.DATABASE_UNAVAILABLE;
import static com.stageaccord.sharedkernel.application.RejectionCode.FEATURE_WRITE_STOPPED;
import static com.stageaccord.sharedkernel.application.RejectionCode.OBJECT_STORAGE_UNAVAILABLE;
import static com.stageaccord.sharedkernel.application.RejectionCode.SESSION_VALIDATION_UNAVAILABLE;
import static com.stageaccord.sharedkernel.application.RejectionCode.TIME_TRUST_UNAVAILABLE;

import java.time.Duration;
import java.util.Objects;

import com.stageaccord.sharedkernel.application.CommandClass;
import com.stageaccord.sharedkernel.application.CommandRejectedException;
import com.stageaccord.sharedkernel.application.RejectionCode;

public final class RuntimeGuard {
    private static final Duration MAXIMUM_TRUSTED_CLOCK_OFFSET = Duration.ofSeconds(2);

    public void requireAllowed(CommandClass commandClass, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(commandClass, "commandClass");
        Objects.requireNonNull(snapshot, "snapshot");

        if (!snapshot.databaseAvailable()) reject(DATABASE_UNAVAILABLE);
        if (commandClass != CommandClass.PUBLIC_QUERY && !snapshot.sessionStoreAvailable()) {
            reject(SESSION_VALIDATION_UNAVAILABLE);
        }
        if (commandClass.isWrite() && !snapshot.auditAvailable()) reject(AUDIT_UNAVAILABLE);
        if (commandClass.isWrite() && (!snapshot.featureEnabled()
                || snapshot.mode() == RuntimeMode.WRITE_STOPPED
                || snapshot.mode() == RuntimeMode.RECOVERY_VERIFICATION)) {
            reject(FEATURE_WRITE_STOPPED);
        }
        if (commandClass == CommandClass.FILE_COMMAND && !snapshot.objectStorageAvailable()) {
            reject(OBJECT_STORAGE_UNAVAILABLE);
        }
        if (commandClass == CommandClass.TIME_SENSITIVE_COMMAND
                && snapshot.clockOffset().compareTo(MAXIMUM_TRUSTED_CLOCK_OFFSET) > 0) {
            reject(TIME_TRUST_UNAVAILABLE);
        }
    }

    private void reject(RejectionCode code) {
        throw CommandRejectedException.of(code);
    }
}
