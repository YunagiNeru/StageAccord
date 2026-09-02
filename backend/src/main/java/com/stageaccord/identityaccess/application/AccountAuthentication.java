package com.stageaccord.identityaccess.application;

import java.util.UUID;

public record AccountAuthentication(UUID accountId, String status, int authGeneration,
        String encodedPassword, ProtectedValue protectedTotpSecret) {}
