package com.stageaccord.collaboration.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class ApprovalPolicy {
    private static final Duration FRESH_AUTH_WINDOW = Duration.ofMinutes(30);

    public void requireAction(byte[] expectedTargetHash, Action action, Instant now, boolean timeTrusted) {
        if (!timeTrusted || now == null) fail(CollaborationRuleViolation.Reason.TIME_TRUST_UNAVAILABLE);
        if (!action.eligibleApprover()) fail(CollaborationRuleViolation.Reason.APPROVER_NOT_ELIGIBLE);
        if (!action.explicitUserAction()) fail(CollaborationRuleViolation.Reason.EXPLICIT_ACTION_REQUIRED);
        if (action.authenticatedAt() == null || action.authenticatedAt().isAfter(now)
                || Duration.between(action.authenticatedAt(), now).compareTo(FRESH_AUTH_WINDOW) > 0) {
            fail(CollaborationRuleViolation.Reason.AUTHENTICATION_STALE);
        }
        if (!Arrays.equals(expectedTargetHash, action.targetHash())) {
            fail(CollaborationRuleViolation.Reason.TARGET_VERSION_MISMATCH);
        }
    }

    public boolean isSatisfied(byte[] expectedTargetHash, int requiredApprovals, List<Action> actions) {
        if (requiredApprovals < 1 || actions == null) return false;
        var approvers = new HashSet<String>();
        for (Action action : actions) {
            if (action.decision() == Decision.REJECTED || !action.explicitUserAction()
                    || !action.eligibleApprover() || !Arrays.equals(expectedTargetHash, action.targetHash())) return false;
            approvers.add(action.actorId());
        }
        return approvers.size() >= requiredApprovals;
    }

    private static void fail(CollaborationRuleViolation.Reason reason) {
        throw new CollaborationRuleViolation(reason);
    }

    public record Action(String actorId, boolean eligibleApprover, boolean explicitUserAction,
            Instant authenticatedAt, byte[] targetHash, Decision decision) {
        public Action { targetHash = targetHash == null ? null : targetHash.clone(); }
        @Override public byte[] targetHash() { return targetHash == null ? null : targetHash.clone(); }
    }
    public enum Decision { APPROVED, REJECTED }
}
