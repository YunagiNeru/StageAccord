package com.stageaccord.bootstrap;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

final class RuntimeConfigurationGuard implements EnvironmentPostProcessor {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "0.0.0.0");
    private static final Map<String, Integer> SECRET_MINIMUM_LENGTHS = Map.ofEntries(
            Map.entry("stage-accord.database.username", 1),
            Map.entry("stage-accord.database.password", 1),
            Map.entry("stage-accord.valkey.username", 1),
            Map.entry("stage-accord.valkey.password", 1),
            Map.entry("stage-accord.object-storage.application.access-key-id", 16),
            Map.entry("stage-accord.object-storage.application.secret-access-key", 32),
            Map.entry("stage-accord.object-storage.worker.access-key-id", 16),
            Map.entry("stage-accord.object-storage.worker.secret-access-key", 32),
            Map.entry("stage-accord.billing.stripe.api-key", 16),
            Map.entry("stage-accord.billing.stripe.webhook-secret", 16),
            Map.entry("stage-accord.mail.ses.access-key-id", 1),
            Map.entry("stage-accord.mail.ses.secret-access-key", 16),
            Map.entry("stage-accord.security.session-hmac-key", 32),
            Map.entry("stage-accord.security.csrf-hmac-key", 32),
            Map.entry("stage-accord.security.field-encryption-key", 32));
    private static final List<String> SHARED_SECRETS = List.of(
            "stage-accord.database.username", "stage-accord.database.password",
            "stage-accord.valkey.username", "stage-accord.valkey.password",
            "stage-accord.security.field-encryption-key");
    private static final List<String> APPLICATION_SECRETS = List.of(
            "stage-accord.object-storage.application.access-key-id",
            "stage-accord.object-storage.application.secret-access-key",
            "stage-accord.billing.stripe.api-key", "stage-accord.billing.stripe.webhook-secret",
            "stage-accord.security.session-hmac-key", "stage-accord.security.csrf-hmac-key");
    private static final List<String> WORKER_SECRETS = List.of(
            "stage-accord.object-storage.worker.access-key-id",
            "stage-accord.object-storage.worker.secret-access-key",
            "stage-accord.mail.ses.access-key-id", "stage-accord.mail.ses.secret-access-key");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    void validate(Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        boolean production = environment.acceptsProfiles(Profiles.of("production"));
        boolean application = environment.acceptsProfiles(Profiles.of("app"));
        boolean worker = environment.acceptsProfiles(Profiles.of("worker"));
        if (local == production) throw invalid("active environment profile");
        if (application == worker) throw invalid("active runtime role");

        requireExact(environment, "stage-accord.environment", production ? "production" : "local");
        if (!production) return;

        validateDatabaseAndValkey(environment);
        validateAmazonS3(environment);
        validateScanMode(environment);
        validateWebAuthn(environment);
        validateSecrets(environment, application, worker);
        if (application) validateStripeMode(environment);
    }

    private void validateDatabaseAndValkey(Environment environment) {
        URI database = URI.create(require(environment, "stage-accord.database.source-url").replaceFirst("^jdbc:", ""));
        String databaseQuery = database.getQuery() == null ? "" : database.getQuery();
        if (!"postgresql".equalsIgnoreCase(database.getScheme()) || database.getHost() == null
                || LOOPBACK_HOSTS.contains(database.getHost().toLowerCase(Locale.ROOT))
                || database.getUserInfo() != null || !databaseQuery.contains("sslmode=verify-full")) {
            throw invalid("stage-accord.database.source-url");
        }
        URI valkey = URI.create(require(environment, "stage-accord.valkey.url"));
        if (!"rediss".equalsIgnoreCase(valkey.getScheme()) || valkey.getHost() == null
                || LOOPBACK_HOSTS.contains(valkey.getHost().toLowerCase(Locale.ROOT))
                || valkey.getUserInfo() != null) {
            throw invalid("stage-accord.valkey.url");
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

    private void validateSecrets(Environment environment, boolean application, boolean worker) {
        List<String> required = new ArrayList<>(SHARED_SECRETS);
        if (application) required.addAll(APPLICATION_SECRETS);
        if (worker) required.addAll(WORKER_SECRETS);
        for (String property : required) {
            String value = require(environment, property);
            if (value.length() < SECRET_MINIMUM_LENGTHS.get(property)
                    || value.contains("CHANGE_ME") || value.startsWith("test-")) {
                throw invalid(property);
            }
        }
    }

    private void validateStripeMode(Environment environment) {
        if (!require(environment, "stage-accord.billing.stripe.api-key").startsWith("sk_live_")) {
            throw invalid("stage-accord.billing.stripe.api-key");
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
        return new IllegalStateException("実行構成が未設定または不正です: " + property);
    }
}
