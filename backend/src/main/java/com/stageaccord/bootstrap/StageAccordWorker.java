package com.stageaccord.bootstrap;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootConfiguration
@EnableAutoConfiguration
public class StageAccordWorker {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StageAccordWorker.class);
        application.setDefaultProperties(Map.of("spring.profiles.active", "local,worker"));
        application.run(args);
    }
}
