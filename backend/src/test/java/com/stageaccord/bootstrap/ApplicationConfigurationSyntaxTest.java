package com.stageaccord.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class ApplicationConfigurationSyntaxTest {

    @Test
    void singlePropertiesFileResolvesLocalApplicationProfile() {
        SpringApplication application = new SpringApplication(ConfigurationProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.name=application-test-fixture",
                "--spring.profiles.active=local,app")) {
            assertThat(context.getEnvironment().getProperty("stage-accord.environment")).isEqualTo("local");
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080");
            assertThat(context.getEnvironment().getProperty("stage-accord.worker.inbound-business-routes-enabled"))
                    .isNull();
        }
    }

    @Test
    void singlePropertiesFileResolvesLocalWorkerProfile() {
        SpringApplication application = new SpringApplication(ConfigurationProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.name=application-test-fixture",
                "--spring.profiles.active=local,worker")) {
            assertThat(context.getEnvironment().getProperty("stage-accord.environment")).isEqualTo("local");
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8081");
            assertThat(context.getEnvironment().getProperty("stage-accord.worker.inbound-business-routes-enabled"))
                    .isEqualTo("false");
        }
    }

    @Test
    void obsoleteApplicationYamlResourcesAreAbsent() {
        ClassLoader loader = getClass().getClassLoader();
        assertThat(loader.getResource("application-test-fixture.properties")).isNotNull();
        assertThat(List.of("application.yml", "application-production.yml", "application-app.yml",
                "application-worker.yml")).allSatisfy(name -> assertThat(loader.getResource(name)).isNull());
    }

    @Test
    void deploymentYamlFilesAreSyntacticallyValid() throws IOException {
        Yaml yaml = new Yaml();
        Path projectRoot = Path.of("..").toAbsolutePath().normalize();
        List<Path> files = List.of(
                projectRoot.resolve("deploy/compose.production.yaml"),
                projectRoot.resolve("deploy/ansible/inventory/production/hosts.yml.example"),
                projectRoot.resolve("deploy/ansible/group_vars/all/production.yml"),
                projectRoot.resolve("deploy/ansible/playbooks/prepare-runtime-directories.yml"),
                projectRoot.resolve("deploy/ansible/playbooks/verify-inventory.yml"));

        for (Path file : files) {
            try (InputStream input = Files.newInputStream(file)) {
                Object parsed = yaml.load(input);
                assertThat(parsed).as("root document of %s", file).isInstanceOfAny(Map.class, List.class);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConfigurationProbe {
    }
}
