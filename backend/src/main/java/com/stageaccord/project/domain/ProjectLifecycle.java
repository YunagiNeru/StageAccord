package com.stageaccord.project.domain;

import java.util.List;

public final class ProjectLifecycle {
    private ProjectStatus status = ProjectStatus.PLANNED;
    private ProjectStatus stateBeforeDispute;
    private int activeCheckpoint;

    public void activate(boolean agreementAccepted) {
        require(status == ProjectStatus.PLANNED && agreementAccepted, Reason.TRANSITION_DENIED);
        status = ProjectStatus.ACTIVE;
        activeCheckpoint = 1;
    }

    public void advanceCheckpoint(int sequence, boolean requiredItemsReady, boolean approvalSatisfied) {
        require(status == ProjectStatus.ACTIVE && sequence == activeCheckpoint, Reason.CHECKPOINT_ORDER_VIOLATION);
        require(requiredItemsReady, Reason.REQUIRED_ITEM_MISSING);
        require(approvalSatisfied, Reason.APPROVAL_INCOMPLETE);
        activeCheckpoint++;
    }

    public void hold() {
        require(status == ProjectStatus.ACTIVE, Reason.TRANSITION_DENIED);
        status = ProjectStatus.ON_HOLD;
    }

    public void resume() {
        require(status == ProjectStatus.ON_HOLD, Reason.TRANSITION_DENIED);
        status = ProjectStatus.ACTIVE;
    }

    public void openDispute() {
        require(status == ProjectStatus.ACTIVE || status == ProjectStatus.ON_HOLD, Reason.TRANSITION_DENIED);
        stateBeforeDispute = status;
        status = ProjectStatus.DISPUTED;
    }

    public void resolveDispute(ProjectStatus requestedReturnState) {
        require(status == ProjectStatus.DISPUTED && requestedReturnState == stateBeforeDispute,
                Reason.INVALID_DISPUTE_RETURN_STATE);
        status = stateBeforeDispute;
        stateBeforeDispute = null;
    }

    public void cancel(List<Boolean> requiredConfirmations) {
        require(status != ProjectStatus.COMPLETED && status != ProjectStatus.CANCELLED, Reason.TRANSITION_DENIED);
        require(!requiredConfirmations.isEmpty() && requiredConfirmations.stream().allMatch(Boolean.TRUE::equals),
                Reason.CANCELLATION_CONFIRMATION_MISSING);
        status = ProjectStatus.CANCELLED;
    }

    public ProjectStatus status() { return status; }
    public int activeCheckpoint() { return activeCheckpoint; }

    private static void require(boolean condition, Reason reason) {
        if (!condition) throw new ProjectRuleViolation(reason);
    }

    public enum ProjectStatus { PLANNED, ACTIVE, ON_HOLD, DISPUTED, CANCELLED, COMPLETED }
    public enum Reason { TRANSITION_DENIED, CHECKPOINT_ORDER_VIOLATION, REQUIRED_ITEM_MISSING,
        APPROVAL_INCOMPLETE, INVALID_DISPUTE_RETURN_STATE, CANCELLATION_CONFIRMATION_MISSING }
    public static final class ProjectRuleViolation extends RuntimeException {
        private final Reason reason;
        private ProjectRuleViolation(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
