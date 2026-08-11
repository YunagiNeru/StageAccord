package com.stageaccord.bootstrap;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

final class ProductionConfigurationGuard implements EnvironmentPostProcessor {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "0.0.0.0");
    private static final List<String> APPLICATION_FILES = List.of(
            "stage-accord.secrets.db-username-file", "stage-accord.secrets.db-password-file",
            "stage-accord.secrets.valkey-username-file", "stage-accord.secrets.valkey-password-file",
            "stage-accord.secrets.s3-access-key-id-file", "stage-accord.secrets.s3-secret-access-key-file",
            "stage-accord.secrets.stripe-api-key-file", "stage-accord.secrets.stripe-webhook-secret-file",
            "stage-accord.secrets.session-hmac-key-file", "stage-accord.secrets.csrf-hmac-key-file",
            "stage-accord.secrets.field-encryption-key-file");
    private static final List<String> WORKER_FILES = List.of(
            "stage-accord.secrets.s3-worker-access-key-id-file",
            "stage-accord.secrets.s3-worker-secret-access-key-file",
            "stage-accord.secrets.mail-username-file", "stage-accord.secrets.mail-password-file");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    void validate(Environment environment) {
        requireExact(environment, "stage-accord.environment", "production");
        validateAmazonS3(environment);
        validateScanMode(environment);
        validateWebAuthn(environment);
        validateSecretFiles(environment);
        if (environment.matchesProfiles("app")) {
            validateStripeMode(environment);
        }
    }

    private void validateAmazonS3(Environment environment) {
        String region = require(environment, "stage-accord.object-storage.region");
        URI endpoint = URI.create(require(environment, "stage-accord.object-storage.endpoint"));
        String expectedHost = "s3." + region + ".amazonaws.com";
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !expectedHost.equalsIgnoreCase(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getPort() != -1) {
            throw invalid("stage-accord.object-storage.endpoint");
        }
    }

    private void validateScanMode(Environment environment) {
        String mode = require(environment, "stage-accord.malware-scan.mode");
        if (!Set.of("required", "bypass").contains(mode)) {
            throw invalid("stage-accord.malware-scan.mode");
        }
        if ("required".equals(mode)) {
            String host = require(environment, "stage-accord.malware-scan.host").toLowerCase(Locale.ROOT);
            if (LOOPBACK_HOSTS.contains(host) || host.endsWith(".invalid")) {
                throw invalid("stage-accord.malware-scan.host");
            }
        }
    }

    private void validateWebAuthn(Environment environment) {
        String rpId = require(environment, "stage-accord.webauthn.rp-id").toLowerCase(Locale.ROOT);
        if (LOOPBACK_HOSTS.contains(rpId) || rpId.contains("*") || rpId.endsWith(".invalid")) {
            throw invalid("stage-accord.webauthn.rp-id");
        }
        for (String origin : require(environment, "stage-accord.webauthn.allowed-origins").split(",")) {
            URI uri = URI.create(origin.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !rpId.equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null) {
                throw invalid("stage-accord.webauthn.allowed-origins");
            }
        }
    }

    private void validateSecretFiles(Environment environment) {
        List<String> required = new ArrayList<>();
        if (environment.matchesProfiles("app")) required.addAll(APPLICATION_FILES);
        if (environment.matchesProfiles("worker")) required.addAll(WORKER_FILES);
        for (String property : required) {
            Path path = Path.of(require(environment, property));
            if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw invalid(property);
            }
        }
    }

    private void validateStripeMode(Environment environment) {
        Path path = Path.of(require(environment, "stage-accord.secrets.stripe-api-key-file"));
        try {
            if (!Files.readString(path).strip().startsWith("sk_live_")) {
                throw invalid("stage-accord.secrets.stripe-api-key-file");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("本番秘密ファイルを検証できません: stripe-api-key-file", exception);
        }
    }

    private void requireExact(Environment environment, String property, String expected) {
        if (!expected.equals(require(environment, property))) throw invalid(property);
    }

    private String require(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank() || value.contains("__REQUIRED_")) throw invalid(property);
        return value.strip();
    }

    private IllegalStateException invalid(String property) {
        return new IllegalStateException("本番構成が未設定または不正です: " + property);
    }
}
