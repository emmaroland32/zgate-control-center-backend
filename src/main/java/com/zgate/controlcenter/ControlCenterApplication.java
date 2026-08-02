package com.zgate.controlcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
public class ControlCenterApplication {
    public static void main(String[] args) {
        // No default profile. Defaulting to 'dev' meant an install that merely forgot
        // SPRING_PROFILES_ACTIVE ran with dev semantics (relaxed Flyway validation, debug logging)
        // while believing it was production. An unconfigured boot now uses the base profile only.
        SpringApplication app = new SpringApplication(ControlCenterApplication.class);
        app.run(args);
    }
}
