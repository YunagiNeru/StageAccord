package com.stageaccord.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.stageaccord.auditadmin.domain.KillSwitch;
import com.stageaccord.auditadmin.domain.SupportAccessPolicy;
import com.stageaccord.privacy.domain.DeletionWorkflow;

class BillingAdminPrivacyPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Test
    void unverifiedOrOlderStripeEventNeverExpandsEntitlement() {
        var policy = new BillingPolicy();
        var current = new BillingPolicy.CurrentEntitlement(
                BillingPolicy.EntitlementState.RESTRICTED, NOW.minusSeconds(60));
        assertThat(policy.resolveWebhook(current, new BillingPolicy.VerifiedEvent(
                false, true, true, NOW, BillingPolicy.EntitlementState.ACTIVE)))
                .isEqualTo(BillingPolicy.EntitlementState.RESTRICTED);
        assertThat(policy.resolveWebhook(current, new BillingPolicy.VerifiedEvent(
                true, true, false, NOW, BillingPolicy.EntitlementState.ACTIVE)))
                .isEqualTo(BillingPolicy.EntitlementState.RESTRICTED);
        assertThat(policy.resolveWebhook(current, new BillingPolicy.VerifiedEvent(
                true, true, true, NOW, BillingPolicy.EntitlementState.ACTIVE)))
                .isEqualTo(BillingPolicy.EntitlementState.ACTIVE);
    }

    @Test
    void graceEndsAfterSevenDaysAndRestrictionPreservesReadExportAndPayment() {
        var policy = new BillingPolicy();
        assertThat(policy.stateAfterPaymentFailure(NOW, NOW.plusSeconds(7 * 86400L - 1)))
                .isEqualTo(BillingPolicy.EntitlementState.GRACE);
        assertThat(policy.stateAfterPaymentFailure(NOW, NOW.plusSeconds(7 * 86400L)))
                .isEqualTo(BillingPolicy.EntitlementState.RESTRICTED);
        var capabilities = policy.capabilities(BillingPolicy.EntitlementState.RESTRICTED);
        assertThat(capabilities.canRead() && capabilities.canExport() && capabilities.canUpdatePayment()).isTrue();
        assertThat(capabilities.canCreate()).isFalse();
        assertThat(policy.evaluateUsage(80, 1, 100).warnAtEightyPercent()).isTrue();
        assertThat(policy.evaluateUsage(100, 1, 100).blockNewWrites()).isTrue();
    }

    @Test
    void supportAccessRequiresVpnMfaPurposeScopeDifferentApproverAndSixtyMinuteExpiry() {
        var policy = new SupportAccessPolicy();
        var grant = new SupportAccessPolicy.Grant("ticket-1", "調査", "requester", "approver",
                Set.of("read_timeline"), NOW, NOW.plusSeconds(3600), null);
        policy.requireUsable(grant, "read_timeline", NOW.plusSeconds(1), true, true);
        assertThatThrownBy(() -> policy.requireUsable(grant, "read_file", NOW.plusSeconds(1), true, true))
                .isInstanceOf(SupportAccessPolicy.AdminRuleViolation.class);
        assertThatThrownBy(() -> policy.requireUsable(grant, "read_timeline", NOW.plusSeconds(1), false, true))
                .isInstanceOf(SupportAccessPolicy.AdminRuleViolation.class);
    }

    @Test
    void killSwitchNeedsFreshAuthenticationAndDifferentPersonToRelease() {
        var killSwitch = new KillSwitch();
        killSwitch.stop("operator-a", "incident", "checks pass", NOW, NOW);
        assertThat(killSwitch.stopped()).isTrue();
        assertThatThrownBy(() -> killSwitch.release("operator-a", true, NOW, NOW))
                .isInstanceOf(SupportAccessPolicy.AdminRuleViolation.class);
        killSwitch.release("operator-b", true, NOW, NOW);
        assertThat(killSwitch.stopped()).isFalse();
    }

    @Test
    void deletionCannotStartBeforeSignedLedgerAckAndHoldPausesOnlyItsTarget() {
        var workflow = new DeletionWorkflow(NOW);
        assertThat(workflow.deadlines().cacheAndSearch()).isEqualTo(NOW.plusSeconds(86400));
        workflow.markLedgerPending();
        assertThatThrownBy(() -> workflow.acknowledgeIndependentLedger(false))
                .isInstanceOf(DeletionWorkflow.PrivacyRuleViolation.class);
        workflow.acknowledgeIndependentLedger(true);
        workflow.beginProcessing(true);
        assertThat(workflow.status()).isEqualTo(DeletionWorkflow.Status.HELD);
        workflow.resumeAfterHold();
        assertThatThrownBy(() -> workflow.complete(false))
                .isInstanceOf(DeletionWorkflow.PrivacyRuleViolation.class);
        workflow.complete(true);
        assertThat(workflow.status()).isEqualTo(DeletionWorkflow.Status.COMPLETED);
    }
}
