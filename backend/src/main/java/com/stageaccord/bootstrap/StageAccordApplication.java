package com.stageaccord.bootstrap;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootConfiguration
@EnableAutoConfiguration
public class StageAccordApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StageAccordApplication.class);
        application.setDefaultProperties(Map.of(
                "spring.profiles.active", "local,app",
                "spring.config.additional-location",
                "optional:file:./src/main/resources/application.properties,optional:file:./config/application.properties"));
        application.run(args);
    }
}
