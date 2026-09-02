package com.stageaccord.collaboration.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class DeliveryPolicy {
    private static final Duration FRESH_AUTH_WINDOW = Duration.ofMinutes(30);

    public void requireFreezable(List<DeliveryItem> items) {
        if (items == null || items.isEmpty() || items.stream().anyMatch(item -> !item.ready()
                || item.sha256() == null || item.sha256().length != 32 || item.sizeBytes() < 1)) {
            fail(CollaborationRuleViolation.Reason.DELIVERY_ITEM_NOT_READY);
        }
    }

    public void requireReceivable(boolean packageDelivered, byte[] expectedManifestHash,
            Receipt receipt, Instant now, boolean timeTrusted) {
        if (!packageDelivered) fail(CollaborationRuleViolation.Reason.PACKAGE_NOT_DELIVERED);
        if (!timeTrusted || now == null) fail(CollaborationRuleViolation.Reason.TIME_TRUST_UNAVAILABLE);
        if (!receipt.explicitUserAction()) fail(CollaborationRuleViolation.Reason.EXPLICIT_ACTION_REQUIRED);
        if (receipt.authenticatedAt() == null || receipt.authenticatedAt().isAfter(now)
                || Duration.between(receipt.authenticatedAt(), now).compareTo(FRESH_AUTH_WINDOW) > 0) {
            fail(CollaborationRuleViolation.Reason.AUTHENTICATION_STALE);
        }
        if (!Arrays.equals(expectedManifestHash, receipt.manifestHash())) {
            fail(CollaborationRuleViolation.Reason.DELIVERY_MANIFEST_MISMATCH);
        }
    }

    private static void fail(CollaborationRuleViolation.Reason reason) {
        throw new CollaborationRuleViolation(reason);
    }

    public record DeliveryItem(boolean ready, long sizeBytes, byte[] sha256) {
        public DeliveryItem { sha256 = sha256 == null ? null : sha256.clone(); }
        @Override public byte[] sha256() { return sha256 == null ? null : sha256.clone(); }
    }
    public record Receipt(boolean explicitUserAction, Instant authenticatedAt, byte[] manifestHash) {
        public Receipt { manifestHash = manifestHash == null ? null : manifestHash.clone(); }
        @Override public byte[] manifestHash() { return manifestHash == null ? null : manifestHash.clone(); }
    }
}
