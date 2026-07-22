package com.zgate.controlcenter.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "control_center_users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ControlCenterUser {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // @Builder.Default ensures builder().build() gets true, not the primitive default false
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    private LocalDateTime lastLoginAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public enum Role { SUPER_ADMIN, ADMIN, SUPPORT, VIEWER }
}
