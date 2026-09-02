package com.stageaccord.intake.application;

import java.util.UUID;

public interface IntakeRateGate {
    boolean allow(UUID workspaceId, byte[] subjectDigest);
}
