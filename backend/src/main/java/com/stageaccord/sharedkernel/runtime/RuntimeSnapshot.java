package com.stageaccord.sharedkernel.runtime;

import java.time.Duration;
import java.util.Objects;

public record RuntimeSnapshot(
        RuntimeMode mode,
        boolean databaseAvailable,
        boolean sessionStoreAvailable,
        boolean auditAvailable,
        boolean objectStorageAvailable,
        boolean featureEnabled,
        Duration clockOffset) {

    public RuntimeSnapshot {
        Objects.requireNonNull(mode, "mode");
        clockOffset = Objects.requireNonNull(clockOffset, "clockOffset").abs();
    }
}
