package com.stageaccord.identityaccess.application;

import java.util.UUID;

public interface VerificationMessageSender {
    void sendEmailVerification(String email, UUID challengeId, String token);
}
