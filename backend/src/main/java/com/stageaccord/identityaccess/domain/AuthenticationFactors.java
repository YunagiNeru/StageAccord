package com.stageaccord.identityaccess.domain;

public record AuthenticationFactors(int activePasskeys, boolean passwordActive, boolean totpActive) {
    public AuthenticationFactors {
        if (activePasskeys < 0) throw new IllegalArgumentException("activePasskeys must not be negative");
    }

    public boolean canAuthenticate() {
        return activePasskeys > 0 || (passwordActive && totpActive);
    }

    public AuthenticationFactors removePasskey() {
        if (activePasskeys == 0) return this;
        return requireUsable(new AuthenticationFactors(activePasskeys - 1, passwordActive, totpActive));
    }

    public AuthenticationFactors removeTotp() {
        return requireUsable(new AuthenticationFactors(activePasskeys, passwordActive, false));
    }

    public AuthenticationFactors removePassword() {
        return requireUsable(new AuthenticationFactors(activePasskeys, false, totpActive));
    }

    public void requireRegistrationComplete(boolean emailVerified) {
        if (!emailVerified || !canAuthenticate()) reject();
    }

    private AuthenticationFactors requireUsable(AuthenticationFactors changed) {
        if (!changed.canAuthenticate()) reject();
        return changed;
    }

    private static void reject() {
        throw IdentityRuleViolation.of(IdentityRuleViolation.Reason.BUSINESS_RULE_VIOLATION);
    }
}
