package com.stageaccord.sharedkernel.outbox;

public final class DeliveryFailure extends Exception {
    private final boolean permanent;

    public DeliveryFailure(String safeMessage, boolean permanent) {
        super(safeMessage);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
