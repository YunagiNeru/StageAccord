package com.stageaccord.intake.api;

import java.util.UUID;

public interface IntakeGuard {
    void requireAcceptable(UUID workspaceId, byte[] subjectDigest, boolean publishedVersion,
            boolean privacyAccepted, boolean botPassed, boolean senderBlocked,
            boolean intakeOpen, boolean capacityAvailable);
}
