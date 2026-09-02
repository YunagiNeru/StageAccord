package com.stageaccord.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class RuntimeConfigurationGuardTest {

    private static final AtomicBoolean CONTEXT_PROBE_CREATED = new AtomicBoolean();

    @Test
    void registeredGuardRejectsMissingEnvironmentProfileBeforeContextBeansAreCreated() {
        CONTEXT_PROBE_CREATED.set(false);
        SpringApplication application = new SpringApplication(ContextProbeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() -> application.run(
                "--spring.config.name=guard-test",
                "--spring.profiles.active=app"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active environment profile");
        assertThat(CONTEXT_PROBE_CREATED).isFalse();
    }

    @Test
    void acceptsLocalApplicationProfileWithoutProductionValidation() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "app");
        environment.setProperty("stage-accord.environment", "local");

        assertThatCode(() -> new RuntimeConfigurationGuard().validate(environment)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEnvironmentValueThatDoesNotMatchLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "app");
        environment.setProperty("stage-accord.environment", "development");

        assertThatThrownBy(() -> new RuntimeConfigurationGuard().validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.environment");
    }

    @Test
    void rejectsMultipleEnvironmentProfiles() {
        MockEnvironment environment = environmentWithMinimumProductionProperties();
        environment.setActiveProfiles("local", "production", "app");

        assertThatThrownBy(() -> new RuntimeConfigurationGuard().validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active environment profile");
    }

    @Test
    void rejectsMultipleRuntimeRoles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "app", "worker");
        environment.setProperty("stage-accord.environment", "local");

        assertThatThrownBy(() -> new RuntimeConfigurationGuard().validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active runtime role");
    }

    @Test
    void rejectsUnknownMalwareScanModeInProduction() {
        MockEnvironment environment = environmentWithMinimumProductionProperties();
        environment.setProperty("stage-accord.malware-scan.mode", "automatic");

        assertThatThrownBy(() -> new RuntimeConfigurationGuard().validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.malware-scan.mode");
    }

    @Test
    void rejectsLoopbackObjectStorageEndpointInProduction() {
        MockEnvironment environment = environmentWithMinimumProductionProperties();
        environment.setProperty("stage-accord.object-storage.endpoint", "https://127.0.0.1:7480");

        assertThatThrownBy(() -> new RuntimeConfigurationGuard().validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.object-storage.endpoint");
    }

    @Test
    void rejectsLoopbackDatabaseInProduction() {
        MockEnvironment environment = environmentWithMinimumProductionProperties();
        environment.setProperty("stage-accord.database.source-url", "jdbc:postgresql://127.0.0.1:5432/app?sslmode=verify-full");

        assertThatThrownBy(() -> new RuntimeConfigurationGuard().validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.database.source-url");
    }

    private MockEnvironment environmentWithMinimumProductionProperties() {
        Map<String, String> values = new HashMap<>();
        values.put("stage-accord.environment", "production");
        values.put("stage-accord.database.source-url", "jdbc:postgresql://db.example.com:5432/app?sslmode=verify-full");
        values.put("stage-accord.valkey.url", "rediss://cache.example.com:6379");
        values.put("stage-accord.object-storage.region", "ap-northeast-1");
        values.put("stage-accord.object-storage.endpoint", "https://s3.ap-northeast-1.amazonaws.com");
        values.put("stage-accord.malware-scan.mode", "bypass");
        values.put("stage-accord.webauthn.rp-id", "app.example.com");
        values.put("stage-accord.webauthn.allowed-origins", "https://app.example.com");

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production", "app");
        values.forEach(environment::setProperty);
        return environment;
    }

    @Configuration(proxyBeanMethods = false)
    static class ContextProbeConfiguration {

        @Bean
        Object externalConnectionProbe() {
            CONTEXT_PROBE_CREATED.set(true);
            return new Object();
        }
    }
}
