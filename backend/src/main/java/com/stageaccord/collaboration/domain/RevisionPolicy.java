package com.stageaccord.collaboration.domain;

public final class RevisionPolicy {
    public RevisionDecision requireSubmittable(Classification classification, int itemCount,
            int remainingIncludedRounds, boolean revisionWindowOpen, boolean changeOrderAccepted) {
        if (!revisionWindowOpen || itemCount < 1) fail(CollaborationRuleViolation.Reason.REVISION_WINDOW_CLOSED);
        return switch (classification) {
            case IN_SCOPE -> {
                if (remainingIncludedRounds < 1) fail(CollaborationRuleViolation.Reason.REVISION_QUOTA_EXHAUSTED);
                yield new RevisionDecision(true, remainingIncludedRounds - 1);
            }
            case CORRECTION -> new RevisionDecision(false, remainingIncludedRounds);
            case CHANGE_ORDER -> {
                if (!changeOrderAccepted) fail(CollaborationRuleViolation.Reason.CHANGE_ORDER_NOT_ACCEPTED);
                yield new RevisionDecision(false, remainingIncludedRounds);
            }
        };
    }

    private static void fail(CollaborationRuleViolation.Reason reason) {
        throw new CollaborationRuleViolation(reason);
    }

    public enum Classification { IN_SCOPE, CORRECTION, CHANGE_ORDER }
    public record RevisionDecision(boolean consumesOneRound, int remainingIncludedRounds) {}
}
