package com.stageaccord.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
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

class ProductionConfigurationGuardTest {

    private static final AtomicBoolean CONTEXT_PROBE_CREATED = new AtomicBoolean();

    @Test
    void registeredGuardRejectsConfigurationBeforeContextBeansAreCreated() {
        CONTEXT_PROBE_CREATED.set(false);
        SpringApplication application = new SpringApplication(ContextProbeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() -> application.run(
                "--spring.config.name=guard-test",
                "--stage-accord.environment=development"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.environment");
        assertThat(CONTEXT_PROBE_CREATED).isFalse();
    }

    @Test
    void rejectsNonProductionEnvironmentBeforeExternalConnections() {
        MockEnvironment environment = environmentWithMinimumProperties();
        environment.setProperty("stage-accord.environment", "development");

        ProductionConfigurationGuard guard = new ProductionConfigurationGuard();

        assertThatThrownBy(() -> guard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.environment");
    }

    @Test
    void rejectsUnknownMalwareScanMode() {
        MockEnvironment environment = environmentWithMinimumProperties();
        environment.setProperty("stage-accord.malware-scan.mode", "automatic");

        ProductionConfigurationGuard guard = new ProductionConfigurationGuard();

        assertThatThrownBy(() -> guard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.malware-scan.mode");
    }

    @Test
    void rejectsLoopbackObjectStorageEndpoint() {
        MockEnvironment environment = environmentWithMinimumProperties();
        environment.setProperty("stage-accord.object-storage.endpoint", "https://127.0.0.1:7480");

        ProductionConfigurationGuard guard = new ProductionConfigurationGuard();

        assertThatThrownBy(() -> guard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage-accord.object-storage.endpoint");
    }

    private MockEnvironment environmentWithMinimumProperties() {
        Map<String, String> values = new HashMap<>();
        values.put("stage-accord.environment", "production");
        values.put("stage-accord.object-storage.region", "ap-northeast-1");
        values.put("stage-accord.object-storage.endpoint", "https://s3.ap-northeast-1.amazonaws.com");
        values.put("stage-accord.malware-scan.mode", "bypass");
        values.put("stage-accord.webauthn.rp-id", "app.example.com");
        values.put("stage-accord.webauthn.allowed-origins", "https://app.example.com");

        MockEnvironment environment = new MockEnvironment();
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
