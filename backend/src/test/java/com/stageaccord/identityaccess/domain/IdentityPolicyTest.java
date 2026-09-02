package com.stageaccord.identityaccess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdentityPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    @Test
    void registrationNeedsVerifiedEmailAndACompleteFactorPath() {
        assertRejected(() -> new AuthenticationFactors(1, false, false).requireRegistrationComplete(false),
                IdentityRuleViolation.Reason.BUSINESS_RULE_VIOLATION);
        assertRejected(() -> new AuthenticationFactors(0, true, false).requireRegistrationComplete(true),
                IdentityRuleViolation.Reason.BUSINESS_RULE_VIOLATION);
        new AuthenticationFactors(0, true, true).requireRegistrationComplete(true);
    }

    @Test
    void factorRemovalNeverLeavesPasswordOrTotpAsASingleFactor() {
        assertRejected(() -> new AuthenticationFactors(1, true, false).removePasskey(),
                IdentityRuleViolation.Reason.BUSINESS_RULE_VIOLATION);
        assertRejected(() -> new AuthenticationFactors(0, true, true).removeTotp(),
                IdentityRuleViolation.Reason.BUSINESS_RULE_VIOLATION);
        assertThat(new AuthenticationFactors(1, true, true).removeTotp().canAuthenticate()).isTrue();
    }

    @Test
    void sessionFailsClosedForValkeyLossGenerationChangeAndExpiry() {
        var session = new SessionState(4, AuthStrength.PASSKEY, NOW.minusSeconds(60),
                NOW.minusSeconds(60), NOW.plusSeconds(60), false);
        assertRejected(() -> session.requireValid(4, NOW, false), IdentityRuleViolation.Reason.SESSION_VALIDATION_UNAVAILABLE);
        assertRejected(() -> session.requireValid(5, NOW, true), IdentityRuleViolation.Reason.AUTHENTICATION_REQUIRED);
        assertRejected(() -> session.requireValid(4, NOW.plusSeconds(61), true), IdentityRuleViolation.Reason.AUTHENTICATION_REQUIRED);
    }

    @Test
    void importantOperationRequiresAuthenticationWithinThirtyMinutes() {
        var session = new SessionState(1, AuthStrength.PASSWORD_TOTP, NOW.minusSeconds(1801),
                NOW.minusSeconds(10), NOW.plusSeconds(60), false);
        assertRejected(() -> session.requireFresh(1, NOW, true), IdentityRuleViolation.Reason.AUTHENTICATION_REQUIRED);
    }

    @Test
    void manualRecoveryRequiresDifferentApproverAndWaitPeriod() {
        UUID requester = UUID.randomUUID();
        assertRejected(() -> new RecoveryApproval(NOW, requester, requester),
                IdentityRuleViolation.Reason.BUSINESS_RULE_VIOLATION);
        var approval = new RecoveryApproval(NOW.plusSeconds(72 * 60 * 60), requester, UUID.randomUUID());
        assertRejected(() -> approval.requireCompletable(NOW), IdentityRuleViolation.Reason.RECOVERY_WAIT_ACTIVE);
        approval.requireCompletable(NOW.plusSeconds(72 * 60 * 60));
    }

    @Test
    void recoveryInvalidatesEveryExistingAuthorizationMedium() {
        assertThat(new AuthorizationGenerations(1, 2, 3, 4, 5).invalidateAll())
                .isEqualTo(new AuthorizationGenerations(2, 3, 4, 5, 6));
    }

    private static void assertRejected(Runnable action, IdentityRuleViolation.Reason expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IdentityRuleViolation.class,
                        error -> assertThat(error.reason()).isEqualTo(expected));
    }
}
