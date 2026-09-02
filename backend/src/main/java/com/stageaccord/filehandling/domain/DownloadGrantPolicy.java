package com.stageaccord.filehandling.domain;

import java.time.Instant;

public final class DownloadGrantPolicy {
    public void requireUsable(Grant grant, Instant now, Authorization authorization) {
        if (grant == null || !grant.fileReady()) fail(FileRuleViolation.Reason.FILE_NOT_READY);
        if (authorization == null || !authorization.available()) {
            fail(FileRuleViolation.Reason.AUTHORIZATION_UNAVAILABLE);
        }
        if (grant.authGeneration() != authorization.currentGeneration()) {
            fail(FileRuleViolation.Reason.AUTHORIZATION_STALE);
        }
        if (!authorization.relatedToProject()) fail(FileRuleViolation.Reason.ACCESS_DENIED);
        if (grant.revokedAt() != null) fail(FileRuleViolation.Reason.GRANT_REVOKED);
        if (now == null || !now.isBefore(grant.expiresAt())) fail(FileRuleViolation.Reason.GRANT_EXPIRED);
        if (grant.remainingUses() < 1) fail(FileRuleViolation.Reason.GRANT_EXHAUSTED);
    }

    private static void fail(FileRuleViolation.Reason reason) { throw new FileRuleViolation(reason); }

    public record Grant(boolean fileReady, int authGeneration, Instant expiresAt,
            Instant revokedAt, int remainingUses) {}
    public record Authorization(boolean available, int currentGeneration, boolean relatedToProject) {}
}
