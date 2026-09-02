package com.stageaccord.sharedkernel.outbox;

@FunctionalInterface
public interface OutboxDelivery {
    void deliver(OutboxLease lease) throws DeliveryFailure;
}
