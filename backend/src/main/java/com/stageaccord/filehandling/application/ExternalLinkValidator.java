package com.stageaccord.filehandling.application;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

import com.stageaccord.filehandling.domain.ExternalHostPolicy;
import com.stageaccord.filehandling.domain.FileRuleViolation;

public final class ExternalLinkValidator {
    private final ExternalHostPolicy hostPolicy;

    public ExternalLinkValidator(ExternalHostPolicy hostPolicy) {
        this.hostPolicy = hostPolicy;
    }

    public ValidatedExternalLink validateWithoutNetworkAccess(String value) {
        try {
            if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) fail();
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getUserInfo() != null
                    || uri.getHost() == null) fail();
            String host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            hostPolicy.requireAllowed(host);
            return new ValidatedExternalLink(uri.normalize().toASCIIString(), host);
        } catch (IllegalArgumentException exception) {
            throw new FileRuleViolation(FileRuleViolation.Reason.INVALID_EXTERNAL_URL);
        }
    }

    private static void fail() { throw new IllegalArgumentException("invalid external URL"); }

    public record ValidatedExternalLink(String normalizedUrl, String asciiHost) {}
}
