package com.fxbrief;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.fxbrief")
@EnableScheduling
public class FxBriefApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxBriefApplication.class, args);
    }
}
