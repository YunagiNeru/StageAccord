package com.stageaccord.identityaccess.application;

import java.util.UUID;

public record AuthFactorDescriptor(UUID credentialId, String type, String status) {}
