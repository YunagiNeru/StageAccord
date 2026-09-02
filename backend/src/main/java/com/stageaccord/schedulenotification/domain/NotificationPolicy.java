package com.stageaccord.schedulenotification.domain;

import java.util.HashSet;
import java.util.Set;

public final class NotificationPolicy {
    private final Set<DedupeKey> requested = new HashSet<>();

    public void requireAllowedPreference(Category category, Mode mode) {
        if (category != Category.ACTIVITY && mode == Mode.DISABLED) {
            throw new NotificationRuleViolation(Reason.MANDATORY_NOTIFICATION_CANNOT_BE_DISABLED);
        }
    }

    public boolean reserveOnce(DedupeKey key) {
        if (key == null) throw new NotificationRuleViolation(Reason.INVALID_DEDUPE_KEY);
        return requested.add(key);
    }

    public enum Category { SECURITY, TRANSACTION, ACTIVITY }
    public enum Mode { IMMEDIATE, DIGEST, DISABLED }
    public enum Reason { MANDATORY_NOTIFICATION_CANNOT_BE_DISABLED, INVALID_DEDUPE_KEY }
    public record DedupeKey(String eventId, String principalId, String templateKey) {}
    public static final class NotificationRuleViolation extends RuntimeException {
        private final Reason reason;
        private NotificationRuleViolation(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
