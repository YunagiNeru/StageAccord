package com.stageaccord.bootstrap;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("com.stageaccord")
public class StageAccordWorker {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StageAccordWorker.class);
        application.setDefaultProperties(Map.of(
                "spring.profiles.active", "local,worker",
                "spring.config.additional-location",
                "optional:file:./src/main/resources/application.properties,optional:file:./config/application.properties"));
        application.run(args);
    }
}
