package com.stageaccord.filehandling.domain;

public final class ExternalHostPolicy {
    public void requireAllowed(String asciiHost) {
        if (asciiHost == null || asciiHost.isBlank() || isLiteralAddress(asciiHost)
                || asciiHost.equals("localhost") || asciiHost.endsWith(".localhost")
                || reservedSuffix(asciiHost)) {
            throw new FileRuleViolation(FileRuleViolation.Reason.INVALID_EXTERNAL_URL);
        }
    }

    private static boolean reservedSuffix(String host) {
        return host.equals("invalid") || host.endsWith(".invalid")
                || host.equals("test") || host.endsWith(".test")
                || host.equals("example") || host.endsWith(".example");
    }

    private static boolean isLiteralAddress(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }
}
