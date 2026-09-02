package com.stageaccord.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stageaccord.collaboration.domain.ApprovalPolicy.Action;
import com.stageaccord.collaboration.domain.ApprovalPolicy.Decision;
import com.stageaccord.schedulenotification.domain.NotificationPolicy;

class CollaborationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final byte[] HASH = new byte[32];

    @Test
    void privateProgressNeverAppearsToClientAndPreviewShowsChosenVisibility() {
        var policy = new ProgressVisibilityPolicy();
        assertThat(policy.clientCanRead(ProgressVisibilityPolicy.Visibility.PRIVATE,
                ProgressVisibilityPolicy.PublicationStatus.PUBLISHED)).isFalse();
        assertThat(policy.clientCanRead(ProgressVisibilityPolicy.Visibility.CLIENT,
                ProgressVisibilityPolicy.PublicationStatus.PUBLISHED)).isTrue();
        assertThat(policy.preview("進捗", ProgressVisibilityPolicy.Visibility.CLIENT).visibleToClient()).isTrue();
    }

    @Test
    void revisionRoundCountsBundleOnceAndCorrectionKeepsQuota() {
        var policy = new RevisionPolicy();
        var included = policy.requireSubmittable(RevisionPolicy.Classification.IN_SCOPE, 4, 2, true, false);
        assertThat(included.remainingIncludedRounds()).isEqualTo(1);
        var correction = policy.requireSubmittable(RevisionPolicy.Classification.CORRECTION, 3, 1, true, false);
        assertThat(correction.remainingIncludedRounds()).isEqualTo(1);
        assertThatThrownBy(() -> policy.requireSubmittable(
                RevisionPolicy.Classification.CHANGE_ORDER, 1, 1, true, false))
                .isInstanceOf(CollaborationRuleViolation.class);
    }

    @Test
    void approvalRequiresExplicitFreshActionOnExactVersionAndEveryRequiredParty() {
        var policy = new ApprovalPolicy();
        Action first = new Action("client-a", true, true, NOW.minusSeconds(60), HASH, Decision.APPROVED);
        Action second = new Action("client-b", true, true, NOW.minusSeconds(60), HASH, Decision.APPROVED);
        policy.requireAction(HASH, first, NOW, true);
        assertThat(policy.isSatisfied(HASH, 2, List.of(first))).isFalse();
        assertThat(policy.isSatisfied(HASH, 2, List.of(first, second))).isTrue();
        assertThatThrownBy(() -> policy.requireAction(HASH,
                new Action("system", true, false, NOW, HASH, Decision.APPROVED), NOW, true))
                .isInstanceOf(CollaborationRuleViolation.class);
        assertThat(policy.isSatisfied(new byte[] {1}, 2, List.of(first, second))).isFalse();
    }

    @Test
    void deliveryFreezesOnlyReadyVersionsAndReceiptIsNeverAutomatic() {
        var policy = new DeliveryPolicy();
        policy.requireFreezable(List.of(new DeliveryPolicy.DeliveryItem(true, 12, HASH)));
        assertThatThrownBy(() -> policy.requireFreezable(
                List.of(new DeliveryPolicy.DeliveryItem(false, 12, HASH))))
                .isInstanceOf(CollaborationRuleViolation.class);
        assertThatThrownBy(() -> policy.requireReceivable(true, HASH,
                new DeliveryPolicy.Receipt(false, NOW, HASH), NOW, true))
                .isInstanceOf(CollaborationRuleViolation.class);
        policy.requireReceivable(true, HASH,
                new DeliveryPolicy.Receipt(true, NOW.minusSeconds(60), HASH), NOW, true);
    }

    @Test
    void mandatoryNotificationsCannotBeDisabledAndDuplicateEventIsReservedOnce() {
        var policy = new NotificationPolicy();
        assertThatThrownBy(() -> policy.requireAllowedPreference(
                NotificationPolicy.Category.SECURITY, NotificationPolicy.Mode.DISABLED))
                .isInstanceOf(NotificationPolicy.NotificationRuleViolation.class);
        policy.requireAllowedPreference(NotificationPolicy.Category.ACTIVITY, NotificationPolicy.Mode.DISABLED);
        var key = new NotificationPolicy.DedupeKey("event", "principal", "template");
        assertThat(policy.reserveOnce(key)).isTrue();
        assertThat(policy.reserveOnce(key)).isFalse();
    }
}
