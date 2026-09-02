package com.stageaccord.publiccatalog.domain;

public final class PublicationPolicy {
    public void requirePublishable(boolean requiredFieldsComplete, boolean workflowComplete,
            boolean entitlementAllowsPublication, boolean writeFeatureAvailable) {
        if (!writeFeatureAvailable) reject(Reason.FEATURE_WRITE_STOPPED);
        if (!entitlementAllowsPublication) reject(Reason.ENTITLEMENT_DENIED);
        if (!requiredFieldsComplete || !workflowComplete) reject(Reason.INCOMPLETE_DRAFT);
    }

    private static void reject(Reason reason) { throw new CatalogRuleViolation(reason); }

    public enum Reason { FEATURE_WRITE_STOPPED, ENTITLEMENT_DENIED, INCOMPLETE_DRAFT }

    public static final class CatalogRuleViolation extends RuntimeException {
        private final Reason reason;
        private CatalogRuleViolation(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
