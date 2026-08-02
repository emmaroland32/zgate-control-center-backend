package com.zgate.controlcenter.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "control_center_config")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ControlCenterConfig {

    @Id @UuidGenerator
    private UUID id;

    @JsonProperty("key")
    @Column(name = "config_key", nullable = false, unique = true, length = 200)
    private String configKey;

    @Column(columnDefinition = "TEXT")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String value;

    /**
     * Serialized form of {@link #value}: a row flagged {@code isSecret} returns a marker instead of
     * the stored value. Operators type registry passwords and API tokens into these rows, and the
     * read endpoints are visible to every admin — the value itself never needs to leave the server.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("value")
    public String getMaskedValue() {
        if (!isSecret) return value;
        return value == null || value.isBlank() ? null : "••••••••";
    }

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 100)
    private String category;

    @JsonProperty("type")
    @Column(name = "config_type", nullable = false, length = 20)
    @Builder.Default
    private String configType = "string";

    @Column(name = "is_secret", nullable = false)
    private boolean isSecret;

    @Column(nullable = false)
    @Builder.Default
    private boolean required = false;

    @Column(name = "updated_by")
    private String updatedBy;

    @JsonProperty("lastUpdated")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
