package com.stageaccord.intake;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.stageaccord.intake.api.IntakeGuard;
import com.stageaccord.intake.application.IntakeRateGate;
import com.stageaccord.intake.domain.IntakePolicy;
import com.stageaccord.sharedkernel.web.ApiFailure;

@Component
final class DefaultIntakeGuard implements IntakeGuard {
    private final IntakeRateGate rates;
    private final IntakePolicy policy = new IntakePolicy();

    DefaultIntakeGuard(IntakeRateGate rates) {
        this.rates = rates;
    }

    @Override
    public void requireAcceptable(UUID workspaceId, byte[] subjectDigest, boolean publishedVersion,
            boolean privacyAccepted, boolean botPassed, boolean senderBlocked,
            boolean intakeOpen, boolean capacityAvailable) {
        try {
            policy.requireAcceptable(new IntakePolicy.Evaluation(rates.allow(workspaceId, subjectDigest),
                    true, publishedVersion, privacyAccepted, botPassed, senderBlocked,
                    intakeOpen, capacityAvailable));
        } catch (IntakePolicy.IntakeRuleViolation failure) {
            HttpStatus status = switch (failure.reason()) {
                case RATE_SERVICE_UNAVAILABLE, FEATURE_WRITE_STOPPED -> HttpStatus.SERVICE_UNAVAILABLE;
                case BOT_REJECTED, SENDER_BLOCKED -> HttpStatus.FORBIDDEN;
                case INTAKE_STOPPED -> HttpStatus.TOO_MANY_REQUESTS;
                case VERSION_NOT_PUBLISHED, PRIVACY_NOT_ACCEPTED -> HttpStatus.CONFLICT;
            };
            throw ApiFailure.of(status, failure.reason().name());
        }
    }
}
