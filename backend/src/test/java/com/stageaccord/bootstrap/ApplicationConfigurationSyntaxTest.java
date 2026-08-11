package com.stageaccord.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ApplicationConfigurationSyntaxTest {

    private static final List<String> CONFIGURATION_RESOURCES = List.of(
            "application.yml",
            "application-production.yml",
            "application-app.yml",
            "application-worker.yml");

    @Test
    void allConfigurationResourcesAreValidYamlMappings() throws IOException {
        Yaml yaml = new Yaml();

        for (String resourceName : CONFIGURATION_RESOURCES) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourceName)) {
                assertThat(input).as("resource %s", resourceName).isNotNull();
                Object parsed = yaml.load(input);
                assertThat(parsed).as("root mapping of %s", resourceName).isInstanceOf(Map.class);
            }
        }
    }
}
