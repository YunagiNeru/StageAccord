package com.stageaccord.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootConfiguration
@EnableAutoConfiguration
public class StageAccordApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StageAccordApplication.class);
        application.setAdditionalProfiles("production", "app");
        application.run(args);
    }
}
