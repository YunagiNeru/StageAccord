package com.stageaccord.auditadmin.domain;

import java.time.Instant;

public final class KillSwitch {
    private boolean stopped;
    private String initiatedBy;

    public void stop(String actorId, String reason, String releaseCondition, Instant authenticatedAt, Instant now) {
        requireFresh(actorId, authenticatedAt, now);
        if (reason == null || reason.isBlank() || releaseCondition == null || releaseCondition.isBlank()) fail();
        stopped = true;
        initiatedBy = actorId;
    }

    public void release(String actorId, boolean secondPersonConfirmed, Instant authenticatedAt, Instant now) {
        requireFresh(actorId, authenticatedAt, now);
        if (!stopped || !secondPersonConfirmed || actorId.equals(initiatedBy)) fail();
        stopped = false;
    }

    public boolean stopped() { return stopped; }

    private static void requireFresh(String actorId, Instant authenticatedAt, Instant now) {
        if (actorId == null || authenticatedAt == null || now == null || authenticatedAt.isAfter(now)
                || now.isAfter(authenticatedAt.plusSeconds(1800))) fail();
    }

    private static void fail() {
        throw new SupportAccessPolicy.AdminRuleViolation(SupportAccessPolicy.Reason.RELEASE_CONFIRMATION_REQUIRED);
    }
}
