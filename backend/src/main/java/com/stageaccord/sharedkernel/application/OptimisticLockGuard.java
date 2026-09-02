package com.stageaccord.sharedkernel.application;

public final class OptimisticLockGuard {
    private OptimisticLockGuard() {}

    public static void requireVersion(long currentVersion, long expectedVersion) {
        if (currentVersion != expectedVersion) {
            throw CommandRejectedException.of(RejectionCode.VERSION_CONFLICT);
        }
    }
}
