package com.zgate.nexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
public class NexusApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NexusApplication.class);
        // Default to 'dev' profile when SPRING_PROFILES_ACTIVE is not set.
        // Production deployments must set SPRING_PROFILES_ACTIVE=prod which
        // takes precedence and causes Spring Boot to ignore this default.
        app.setDefaultProperties(Map.of("spring.profiles.default", "dev"));
        app.run(args);
    }
}
