package com.stageaccord.identityaccess.domain;

public record AuthorizationGenerations(int session, int clientLink, int adminElevation,
        int recoveryCode, int downloadGrant) {
    public AuthorizationGenerations {
        if (session < 0 || clientLink < 0 || adminElevation < 0 || recoveryCode < 0 || downloadGrant < 0) {
            throw new IllegalArgumentException("generations must not be negative");
        }
    }

    public AuthorizationGenerations invalidateAll() {
        return new AuthorizationGenerations(increment(session), increment(clientLink),
                increment(adminElevation), increment(recoveryCode), increment(downloadGrant));
    }

    private static int increment(int value) {
        if (value == Integer.MAX_VALUE) throw new IllegalStateException("authorization generation exhausted");
        return value + 1;
    }
}
