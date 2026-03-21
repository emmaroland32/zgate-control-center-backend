package com.zgate.nexus.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * Configures Jackson to accept ISO-8601 datetime strings with or without
 * a timezone suffix (e.g. "2027-01-01T00:00:00" and "2027-01-01T00:00:00.000Z").
 * LocalDateTime is timezone-agnostic; the offset/Z suffix is simply ignored.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        var formatter = new DateTimeFormatterBuilder()
            .append(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            // Optionally consume 'Z' or a numeric offset without requiring it
            .optionalStart()
                .appendOffsetId()
            .optionalEnd()
            .optionalStart()
                .appendLiteral('Z')
            .optionalEnd()
            .toFormatter();

        return builder -> builder.deserializerByType(
            LocalDateTime.class,
            new LocalDateTimeDeserializer(formatter)
        );
    }
}
